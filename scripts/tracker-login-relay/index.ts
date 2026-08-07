// ============================================================
// NuvioTV — QR relay per AniList / Kitsu / MyAnimeList
//
// Contratto con l'app (vedi data/trackerqr/TrackerQrApi.kt):
//   POST /start  {provider}            -> {user_code, url, expires_at, poll_interval_seconds}
//   POST /poll   {user_code, provider} -> {status: pending|approved|expired, payload?, username?}
//
// Implementa 3 provider veri (MAL, AniList, Kitsu) + 1 "mock"
// che approva da solo dopo 2 secondi: permette di testare il QR
// end-to-end senza prima dover registrare un'app sui provider.
//
// Gira identica in locale (deno run --allow-net index.ts) e come
// edge function Supabase (usa Deno.serve, l'entry standard).
// ============================================================

interface RelaySession {
  provider: string;
  userCode: string;
  status: "pending" | "approved" | "expired";
  payload: string | null;
  expiresAt: number;
  pollIntervalSeconds: number;
  state: Record<string, unknown>;
}

const SESSION_TTL_MS = 10 * 60 * 1000; // 10 minuti
const DEFAULT_POLL_INTERVAL_SEC = 3;

// -------- Config dal ambiente (il relay tiene i segreti, non la TV) --------
function env(varName: string): string {
  return Deno.env.get(varName)?.trim() ?? "";
}

const PUBLIC_BASE_URL = env("PUBLIC_BASE_URL") || "http://localhost:8000";
const REDIRECT_URI_BASE = env("REDIRECT_URI_BASE") || PUBLIC_BASE_URL;

const PROVIDERS: Record<string, string> = {
  mal: env("MAL_CLIENT_ID"),
  anilist: env("ANILIST_CLIENT_ID"),
  kitsu: env("KITSU_CLIENT_ID"),
  mock: "mock", // il mock non richiede credenziali
};

// Sessioni in memoria. Per più istanze in produzione servirebbe un DB
// (vedi docs nel README del progetto), per test e uso singolo va bene.
const sessions = new Map<string, RelaySession>();

// -------- Endpoint costanti dei provider --------
const KITSU_AUTHORIZE_URL = "https://kitsu.app/api/oauth/authorize";
const KITSU_TOKEN_URL = "https://kitsu.app/api/oauth/token";
const MAL_AUTHORIZE_URL = "https://myanimelist.net/v1/oauth2/authorize";
const MAL_TOKEN_URL = "https://myanimelist.net/v1/oauth2/token";
const ANILIST_AUTHORIZE_URL = "https://anilist.co/api/v2/oauth/authorize";

// ============================================================
// /start
// ============================================================
async function handleStart(req: Request): Promise<Response> {
  const body = await parseJson<{ provider?: string }>(req);
  const provider = body?.provider?.trim().toLowerCase();
  if (!provider) return json({ error: "missing provider" }, 400);
  if (!(provider in PROVIDERS)) return json({ error: "unsupported provider" }, 400);

  const userCode = generateUserCode();
  const now = Date.now();
  const session: RelaySession = {
    provider,
    userCode,
    status: "pending",
    payload: null,
    expiresAt: now + SESSION_TTL_MS,
    pollIntervalSeconds: DEFAULT_POLL_INTERVAL_SEC,
    state: {},
  };

  const url =
    provider === "mock"
      ? mockUrl(userCode)
      : await buildAuthorizeUrl(provider, PROVIDERS[provider]!, session);
  if (!url) return json({ error: "provider not configured on relay" }, 500);

  sessions.set(userCode, session);

  return json({
    user_code: userCode,
    url,
    expires_at: session.expiresAt,
    poll_interval_seconds: session.pollIntervalSeconds,
  });
}

// ============================================================
// /poll
// ============================================================
async function handlePoll(req: Request): Promise<Response> {
  const body = await parseJson<{ user_code?: string }>(req);
  const userCode = body?.user_code?.trim();
  if (!userCode) return json({ error: "missing user_code" }, 400);
  const session = sessions.get(userCode);
  if (!session || Date.now() > session.expiresAt) {
    return json({ status: "expired" });
  }
  return json({
    status: session.status,
    payload: session.payload,
    username: session.provider === "mock" ? "mock-user" : null,
  });
}

// ============================================================
// /approve — usato dal mock (e da pagine web del relay) per
// marcare una sessione come approved e salvarvi il token.
// ============================================================
async function handleApprove(req: Request): Promise<Response> {
  const body = await parseJson<{ user_code?: string; payload?: string }>(req);
  const userCode = body?.user_code?.trim();
  if (!userCode) return json({ error: "missing user_code" }, 400);
  const session = sessions.get(userCode);
  if (!session) return json({ error: "session not found" }, 404);
  if (Date.now() > session.expiresAt) return json({ error: "session expired" }, 410);
  session.status = "approved";
  session.payload = body?.payload ?? "mock-access-token";
  session.pollIntervalSeconds = 1;
  return json({ ok: true });
}

// ============================================================
// /mock — pagina che il telefono apre dal QR in modalita' mock.
// Approva la sessione dopo ~2 secondi, simulando "utente ha
// autorizzato".
// ============================================================
function mockUrl(userCode: string): string {
  return `${PUBLIC_BASE_URL}/mock?user_code=${encodeURIComponent(userCode)}`;
}

async function handleMock(req: Request): Promise<Response> {
  const url = new URL(req.url);
  const userCode = url.searchParams.get("user_code") ?? "";
  const safeCode = userCode.replace(/[^a-zA-Z0-9]/g, "");
  return html(`<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<style>
  body { background:#0b0b0f; color:#fff; font-family:sans-serif;
         display:flex; align-items:center; justify-content:center; height:100vh; margin:0; }
  .card { text-align:center; max-width:360px; }
  .dot { width:16px; height:16px; border-radius:50%; display:inline-block;
         background:#22c55e; margin-right:8px; animation:pulse 1.2s infinite; }
  @keyframes pulse { 0%,100%{opacity:1} 50%{opacity:.3} }
</style>
</head>
<body>
<div class="card">
  <h2><span class="dot"></span>ACCESSO IN CORSO</h2>
  <p>Modalità test: sto finalizzando sul TV…</p>
</div>
<script>
  const code = ${JSON.stringify(safeCode)};
  setTimeout(() => {
    fetch("/approve", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ user_code: code, payload: "mock-access-token-" + code })
    })
      .then(() => { document.querySelector("h2").textContent = "Fatto! Torna sul TV."; })
      .catch(() => { document.querySelector("h2").textContent = "Errore di approvazione."; });
  }, 2000);
</script>
</body></html>`);
}

// ============================================================
// /callback/{provider} — il provider reindirizza qui DOPO che
// l'utente ha autorizzato sul sito del provider. Lo stato arriva
// come query param `state` = il nostro user_code, cosi' sappiamo
// quale sessione completare.
// ============================================================
async function handleCallback(_req: Request, provider: string): Promise<Response> {
  const url = new URL(_req.url);
  const code = url.searchParams.get("code");
  const state = url.searchParams.get("state");
  const accessToken = url.searchParams.get("access_token");
  const session = state ? sessions.get(state) : undefined;

  if (session) {
    // AniList (implicit): il token arriva come #access_token (fragment),
    // che il server NON vede. Rispondiamo con una pagina HTML che
    // estrae il fragment via JS e lo invia a /approve.
    if (provider === "anilist") {
      const safeCode = state.replace(/[^a-zA-Z0-9]/g, "");
      return html(fragmentCapturePage(safeCode));
    }
    // MAL / Kitsu (auth-code): il codice arriva come query param.
    // Facciamo qui lo scambio con i segreti del relay.
    if (code) {
      const payload = await exchangeCode(provider, session, code);
      if (payload) {
        session.status = "approved";
        session.payload = payload;
      }
    }
  }

  const approved = session?.status === "approved";
  return html(`<!DOCTYPE html>
<html><head><meta charset="utf-8"></head>
<body style="font-family:sans-serif;background:#0b0b0f;color:#fff;display:flex;align-items:center;justify-content:center;height:100vh;margin:0">
  <div style="text-align:center">
    <h2>${approved ? "Puoi chiudere questa finestra e tornare sul TV." : "Nessuna autorizzazione ricevuta. Puoi chiudere questa finestra."}</h2>
  </div>
</body></html>`);
}

// Pagina usata dall'implicit grant (AniList): estrae l'access token dal
// fragment dell'URL e lo consegna al relay.
function fragmentCapturePage(safeCode: string): string {
  return `<!DOCTYPE html>
<html><head><meta charset="utf-8"></head>
<body style="font-family:sans-serif;background:#0b0b0f;color:#fff;display:flex;align-items:center;justify-content:center;height:100vh;margin:0">
  <div style="text-align:center"><h2>Finalizzazione accesso sul TV…</h2></div>
<script>
  const token = location.hash
    .replace(/^#access_token=([^&]*).*/, "$1")
    .replace(/&.*$/, "");
  fetch("/approve", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ user_code: ${JSON.stringify(safeCode)}, payload: token })
  }).then(() => { document.querySelector("h2").textContent = "Fatto! Torna sul TV."; });
</script>
</body></html>`;
}

// ============================================================
// Scambio codice -> token per i provider auth-code
// ============================================================
async function exchangeCode(
  provider: string,
  session: RelaySession,
  code: string,
): Promise<string | null> {
  try {
    if (provider === "mal") {
      const clientId = PROVIDERS.mal;
      const verifier = session.state.codeVerifier as string | undefined;
      if (!clientId || !verifier) return null;
      const tokens = await tokenExchange(MAL_TOKEN_URL, {
        grant_type: "authorization_code",
        client_id: clientId,
        code,
        code_verifier: verifier,
        redirect_uri: `${REDIRECT_URI_BASE}/callback/mal`,
      });
      return tokensJson(tokens);
    }
    if (provider === "kitsu") {
      const clientId = PROVIDERS.kitsu;
      if (!clientId) return null;
      const form: Record<string, string> = {
        grant_type: "authorization_code",
        client_id: clientId,
        code,
        redirect_uri: `${REDIRECT_URI_BASE}/callback/kitsu`,
      };
      const secret = env("KITSU_CLIENT_SECRET");
      if (secret) form.client_secret = secret;
      const tokens = await tokenExchange(KITSU_TOKEN_URL, form);
      return tokensJson(tokens);
    }
    return null;
  } catch (err) {
    console.error("exchangeCode failed", err);
    return null;
  }
}

async function tokenExchange(
  url: string,
  form: Record<string, string>,
): Promise<Record<string, unknown> | null> {
  const body = new URLSearchParams();
  for (const [k, v] of Object.entries(form)) body.set(k, v);
  const resp = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body,
  });
  if (!resp.ok) return null;
  return (await resp.json()).catch(() => null) as Record<string, unknown> | null;
}

// Il payload da consegnare alla TV. Per MAL e Kitsu chiudiamo il JSON
// completo (la app MAL parse già JSON con access_token/refresh_token/expires_in).
function tokensJson(tokens: Record<string, unknown> | null): string | null {
  if (!tokens || typeof tokens.access_token !== "string") return null;
  return JSON.stringify(tokens);
}

// ============================================================
// Helper vari
// ============================================================
async function parseJson<T>(req: Request): Promise<T | null> {
  try {
    return (await req.json()) as T;
  } catch {
    return null;
  }
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json",
      "Access-Control-Allow-Origin": "*",
    },
  });
}

function html(markup: string): Response {
  return new Response(markup, {
    status: 200,
    headers: { "Content-Type": "text/html; charset=utf-8" },
  });
}

function generateUserCode(): string {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  const bytes = new Uint8Array(6);
  crypto.getRandomValues(bytes);
  let code = "";
  for (const b of bytes) code += alphabet[b % alphabet.length];
  return code;
}

// ============================================================
// URL di autorizzazione per ogni provider. Il telefono apre questo
// URL; il provider reindirizza a {REDIRECT_URI_BASE}/callback/{provider}.
// ============================================================
async function buildAuthorizeUrl(
  provider: string,
  clientId: string,
  session: RelaySession,
): Promise<string | null> {
  const redirectUri = `${REDIRECT_URI_BASE}/callback/${provider}`;
  switch (provider) {
    case "mal": return malAuthorizeUrl(clientId, redirectUri, session);
    case "kitsu": return `${KITSU_AUTHORIZE_URL}?response_type=code&client_id=${encodeURIComponent(clientId)}&redirect_uri=${encodeURIComponent(redirectUri)}&state=${session.userCode}`;
    case "anilist": return `${ANILIST_AUTHORIZE_URL}?response_type=token&client_id=${encodeURIComponent(clientId)}&redirect_uri=${encodeURIComponent(redirectUri)}&state=${session.userCode}`;
    default: return null;
  }
}

// MyAnimeList: auth-code + PKCE (S256). Il code_verifier è generato e
// conservato nella sessione (server-side), poi il codice → token.
async function malAuthorizeUrl(
  clientId: string,
  redirectUri: string,
  session: RelaySession,
): Promise<string> {
  const verifier = generateOauthValue(64);
  const challenge = await pkceS256(verifier);
  session.state.codeVerifier = verifier;
  session.state.state = session.userCode;
  return `${MAL_AUTHORIZE_URL}?response_type=code&client_id=${encodeURIComponent(clientId)}&code_challenge=${challenge}&code_challenge_method=S256&state=${session.userCode}&redirect_uri=${encodeURIComponent(redirectUri)}`;
}

function generateOauthValue(minLength: number): string {
  const alphabet =
    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";
  const bytes = new Uint8Array(minLength * 2);
  crypto.getRandomValues(bytes);
  let out = "";
  for (const b of bytes) out += alphabet[b % alphabet.length];
  return out.slice(0, minLength);
}

async function pkceS256(verifier: string): Promise<string> {
  const data = new TextEncoder().encode(verifier);
  const digest = await crypto.subtle.digest("SHA-256", data);
  return base64Url(new Uint8Array(digest));
}

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

// ============================================================
// Router
// ============================================================
function router(req: Request): Promise<Response> {
  const url = new URL(req.url);
  const path = url.pathname;
  if (req.method === "OPTIONS") {
    return Promise.resolve(
      new Response(null, {
        status: 204,
        headers: {
          "Access-Control-Allow-Origin": "*",
          "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
          "Access-Control-Allow-Headers": "Content-Type",
        },
      }),
    );
  }
  switch (true) {
    case path === "/start" && req.method === "POST":
      return handleStart(req);
    case path === "/poll" && req.method === "POST":
      return handlePoll(req);
    case path === "/approve" && req.method === "POST":
      return handleApprove(req);
    case path === "/mock":
      return handleMock(req);
    case path.startsWith("/callback/"):
      return handleCallback(req, path.split("/").pop() ?? "");
    case path === "/":
      return Promise.resolve(
        json({ service: "tracker-login relay", providers: Object.keys(PROVIDERS) }),
      );
    default:
      return Promise.resolve(json({ error: "not found" }, 404));
  }
}

Deno.serve({ port: 8000 }, router);