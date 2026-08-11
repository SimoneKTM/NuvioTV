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
// Gira identica in locale (deno run --allow-env --allow-net index.ts)
// e come edge function Supabase (usa Deno.serve, l'entry standard).
//
// IMPORTANTE: su Supabase le sessioni NON possono stare solo in
// memoria (le istanze sono multiple e vengono ricreate): vengono
// persistite sulla tabella `relay_sessions` del database collegato.
// In locale (senza SUPABASE_URL) si usa una Map in memoria.
// ============================================================

// @ts-ignore - import con runtime
import { createClient, type SupabaseClient } from "npm:@supabase/supabase-js@2.45.4";

interface RelaySession {
  userCode: string;
  provider: string;
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

// URL pubblico del relay. Se non configurato lo ricaviamo dall'Host della
// richiesta in arrivo: così i redirect_uri e l'URL mock sono coerenti con
// l'indirizzo che l'app usa davvero (es. LAN IP nel test su device reale).
const PUBLIC_BASE_URL = env("PUBLIC_BASE_URL");
const REDIRECT_URI_BASE = env("REDIRECT_URI_BASE");

function resolveBaseUrl(req: Request): string {
  const configured = PUBLIC_BASE_URL || REDIRECT_URI_BASE;
  if (configured) return configured;
  const host = req.headers.get("host");
  if (host) return `${req.url.startsWith("https://") ? "https" : "http"}://${host}`;
  return "http://localhost:8000";
}

// Client ID dei provider. Ogni ID deve essere registrato sul provider con
// redirect URI esattamente uguale a `${REDIRECT_URI_BASE}/callback/{provider}`
// (AniList e Kitsu lo richiedono "exact match"). MAL non richiede la registrazione
// del redirect ma usa PKCE.
const PROVIDERS: Record<string, string> = {
  mal: env("MAL_CLIENT_ID"),
  anilist: env("ANILIST_CLIENT_ID"),
  kitsu: env("KITSU_CLIENT_ID"),
  mock: "mock", // il mock non richiede credenziali
};

// Client secret: obbligatorio per AniList (Authorization Code Grant) e opzionale
// per Kitsu. MAL: i client "confidential" (creati dal 2025) lo richiedono nello
// scambio codice -> token; i client vecchi PKCE-only lo lasciano vuoto.
const ANILIST_CLIENT_SECRET = env("ANILIST_CLIENT_SECRET");
const KITSU_CLIENT_SECRET = env("KITSU_CLIENT_SECRET");
const MAL_CLIENT_SECRET = env("MAL_CLIENT_SECRET");

// -------- Session store: Postgres su Supabase, Map in locale --------
interface SessionStore {
  create(session: RelaySession): Promise<void>;
  get(userCode: string): Promise<RelaySession | null>;
  update(
    userCode: string,
    fields: Partial<Pick<RelaySession, "status" | "payload" | "pollIntervalSeconds">>,
  ): Promise<void>;
}

class MemoryStore implements SessionStore {
  private readonly map = new Map<string, RelaySession>();

  async create(session: RelaySession): Promise<void> {
    this.map.set(session.userCode, session);
  }

  async get(userCode: string): Promise<RelaySession | null> {
    return this.map.get(userCode) ?? null;
  }

  async update(
    userCode: string,
    fields: Partial<Pick<RelaySession, "status" | "payload" | "pollIntervalSeconds">>,
  ): Promise<void> {
    const existing = this.map.get(userCode);
    if (existing) {
      this.map.set(userCode, { ...existing, ...fields });
    }
  }
}

class SupabaseStore implements SessionStore {
  private readonly client: SupabaseClient;

  constructor(client: SupabaseClient) {
    this.client = client;
  }

  private rowToSession(row: Record<string, unknown>): RelaySession | null {
    if (typeof row.user_code !== "string") return null;
    return {
      userCode: row.user_code,
      provider: String(row.provider ?? ""),
      status: (row.status as RelaySession["status"]) ?? "pending",
      payload: typeof row.payload === "string" ? row.payload : null,
      expiresAt: Number(row.expires_at ?? 0),
      pollIntervalSeconds: Number(row.poll_interval_seconds ?? DEFAULT_POLL_INTERVAL_SEC),
      state:
        row.state && typeof row.state === "object" && !Array.isArray(row.state)
          ? (row.state as Record<string, unknown>)
          : {},
    };
  }

  async create(session: RelaySession): Promise<void> {
    const { error } = await this.client.from("relay_sessions").insert({
      user_code: session.userCode,
      provider: session.provider,
      status: session.status,
      payload: session.payload,
      state: session.state,
      expires_at: session.expiresAt,
      poll_interval_seconds: session.pollIntervalSeconds,
    });
    if (error) throw new Error(`db insert failed: ${error.message}`);
  }

  async get(userCode: string): Promise<RelaySession | null> {
    const { data, error } = await this.client
      .from("relay_sessions")
      .select("*")
      .eq("user_code", userCode)
      .maybeSingle();
    if (error) throw new Error(`db select failed: ${error.message}`);
    if (!data) return null;
    return this.rowToSession(data as Record<string, unknown>);
  }

  async update(
    userCode: string,
    fields: Partial<Pick<RelaySession, "status" | "payload" | "pollIntervalSeconds">>,
  ): Promise<void> {
    const patch: Record<string, unknown> = {};
    if (fields.status !== undefined) patch.status = fields.status;
    if (fields.payload !== undefined) patch.payload = fields.payload;
    if (fields.pollIntervalSeconds !== undefined) {
      patch.poll_interval_seconds = fields.pollIntervalSeconds;
    }
    const { error } = await this.client
      .from("relay_sessions")
      .update(patch)
      .eq("user_code", userCode);
    if (error) throw new Error(`db update failed: ${error.message}`);
  }
}

function createStore(): SessionStore {
  const url = env("SUPABASE_URL");
  const serviceRoleKey = env("SUPABASE_SERVICE_ROLE_KEY");
  if (url && serviceRoleKey) {
    return new SupabaseStore(createClient(url, serviceRoleKey));
  }
  return new MemoryStore();
}

const store = createStore();

// -------- Endpoint costanti dei provider --------
const KITSU_AUTHORIZE_URL = "https://kitsu.app/api/oauth/authorize";
const KITSU_TOKEN_URL = "https://kitsu.app/api/oauth/token";
const MAL_AUTHORIZE_URL = "https://myanimelist.net/v1/oauth2/authorize";
const MAL_TOKEN_URL = "https://myanimelist.net/v1/oauth2/token";
const ANILIST_AUTHORIZE_URL = "https://anilist.co/api/v2/oauth/authorize";
const ANILIST_TOKEN_URL = "https://anilist.co/api/v2/oauth/token";

// ============================================================
// /start
// ============================================================
async function handleStart(req: Request): Promise<Response> {
  const body = await parseJson<{ provider?: string }>(req);
  const provider = body?.provider?.trim().toLowerCase();
  if (!provider) return json({ error: "missing provider" }, 400);
  if (!(provider in PROVIDERS)) return json({ error: "unsupported provider" }, 400);

  let session: RelaySession;
  for (let attempt = 0; attempt < 5; attempt++) {
    const userCode = generateUserCode();
    session = {
      provider,
      userCode,
      status: "pending",
      payload: null,
      expiresAt: Date.now() + SESSION_TTL_MS,
      pollIntervalSeconds: DEFAULT_POLL_INTERVAL_SEC,
      state: {},
    };
    const url =
      provider === "mock"
        ? mockUrl(userCode, resolveBaseUrl(req))
        : await buildAuthorizeUrl(provider, PROVIDERS[provider] ?? "", session, resolveBaseUrl(req));
    if (!url) return json({ error: "provider not configured on relay" }, 500);
    try {
      await store.create(session);
      return json({
        user_code: userCode,
        url,
        expires_at: session.expiresAt,
        poll_interval_seconds: session.pollIntervalSeconds,
      });
    } catch {
      // collisione di user_code (o DB momentaneamente giù): riprova con un altro codice
      if (attempt === 4) {
        return json({ error: "could not persist session" }, 500);
      }
    }
  }
  return json({ error: "could not persist session" }, 500);
}

// ============================================================
// /poll
// ============================================================
async function handlePoll(req: Request): Promise<Response> {
  const body = await parseJson<{ user_code?: string }>(req);
  const userCode = body?.user_code?.trim();
  if (!userCode) return json({ error: "missing user_code" }, 400);
  let session: RelaySession | null = null;
  try {
    session = await store.get(userCode);
  } catch (error) {
    console.error("poll store error", error);
    return json({ error: "storage unavailable" }, 500);
  }
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
  let session: RelaySession | null = null;
  try {
    session = await store.get(userCode);
  } catch (error) {
    console.error("approve store error", error);
    return json({ error: "storage unavailable" }, 500);
  }
  if (!session) return json({ error: "session not found" }, 404);
  if (Date.now() > session.expiresAt) return json({ error: "session expired" }, 410);
  await store.update(userCode, {
    status: "approved",
    payload: body?.payload ?? "mock-access-token",
    pollIntervalSeconds: 1,
  });
  return json({ ok: true });
}

// ============================================================
// /mock — pagina che il telefono apre dal QR in modalita' mock.
// Approva la sessione dopo ~2 secondi, simulando "utente ha
// autorizzato".
// ============================================================
function mockUrl(userCode: string, baseUrl: string): string {
  return `${baseUrl}/mock?user_code=${encodeURIComponent(userCode)}`;
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
  const authError = url.searchParams.get("error");
  let session: RelaySession | null = null;
  let exchangeError: string | null = null;
  try {
    session = state ? await store.get(state) : null;
  } catch (error) {
    console.error("callback store error", error);
  }

  if (authError) {
    console.error(`provider ${provider} rejected authorization:`, authError);
  }

  if (session && !authError) {
    // AniList / Kitsu / MAL (auth-code): il codice arriva come query param.
    // Facciamo qui lo scambio con le credenziali del relay.
    if (code) {
      const result = await exchangeCode(provider, session, code, resolveBaseUrl(_req));
      if (result.error) {
        // Lo scambio è fallito: il problema è quasi sempre la config del relay
        // (client_id/secret/redirect non allineati al provider). Chiudiamo la
        // sessione così il TV propone "riprova" e mostriamo l'errore vero.
        exchangeError = result.error;
        console.error(`[${provider}] token exchange failed:`, exchangeError);
        try {
          await store.update(session.userCode, {
            status: "expired",
            pollIntervalSeconds: 2,
          });
        } catch (error) {
          console.error("callback update error", error);
        }
      } else if (result.payload) {
        await store.update(session.userCode, {
          status: "approved",
          payload: result.payload,
          pollIntervalSeconds: 1,
        });
        session.status = "approved";
        session.payload = result.payload;
      }
    } else if (accessToken) {
      // Fallback: client AniList registrati come implicit restituiscono il
      // token come query param `access_token`.
      await store.update(session.userCode, {
        status: "approved",
        payload: accessToken,
        pollIntervalSeconds: 1,
      });
      session.status = "approved";
      session.payload = accessToken;
    }
  }

  const approved = session?.status === "approved";
  const message = approved
    ? "Puoi chiudere questa finestra e tornare sul TV."
    : authError
      ? "Hai annullato l'autorizzazione o il provider l'ha rifiutata. Puoi chiudere questa finestra e riprovare dal TV."
      : exchangeError
        ? `Autorizzazione ok, ma lo scambio del token è fallito: ${exchangeError}`
        : "Nessuna autorizzazione ricevuta. Puoi chiudere questa finestra.";
  return html(`<!DOCTYPE html>
<html><head><meta charset="utf-8"></head>
<body style="font-family:sans-serif;background:#0b0b0f;color:#fff;display:flex;align-items:center;justify-content:center;height:100vh;margin:0">
  <div style="text-align:center;max-width:520px;padding:0 24px">
    <h2 style="font-weight:normal">${approved ? "✓" : "✕"}</h2>
    <h3 style="font-weight:normal">${escHtml(message)}</h3>
    ${
      exchangeError
        ? `<p style="color:#9aa0aa;font-size:13px">Controlla che le credenziali del relay (client id/secret e redirect URI ${escHtml(
            `${resolveBaseUrl(_req)}/callback/${provider}`,
          )}) corrispondano a quelle registrate sul provider, poi riprova dal TV.</p>`
        : ""
    }
  </div>
</body></html>`);
}

// ============================================================
// Scambio codice -> token per i provider auth-code
// ============================================================
type ExchangeOutcome =
  | { ok: true; data: Record<string, unknown> }
  | { ok: false; error: string };

interface ExchangeResult {
  payload: string | null;
  error: string | null;
}

function describeError(resp: Response, bodyText: string): string {
  const trimmed = bodyText.trim().replace(/\s+/g, " ").slice(0, 1500);
  return trimmed || `HTTP ${resp.status} ${resp.statusText}`;
}

async function exchangeCode(
  provider: string,
  session: RelaySession,
  code: string,
  requestBaseUrl: string,
): Promise<ExchangeResult> {
  // Il redirect_uri dello scambio deve essere IDENTICO a quello usato
  // nell'authorize URL, altrimenti i provider rispondono con un errore
  // "mismatched redirect_uri".
  const redirectBase = String(session.state.redirectUriBase ?? requestBaseUrl);
  const redirectUri = `${redirectBase}/callback/${provider}`;
  try {
    if (provider === "mal") {
      const clientId = PROVIDERS.mal;
      const verifier = session.state.codeVerifier as string | undefined;
      if (!clientId || !verifier) {
        return { payload: null, error: "MAL non configurato sul relay (manca MAL_CLIENT_ID o il verifier PKCE)" };
      }
      const outcome = await tokenExchange(MAL_TOKEN_URL, {
        grant_type: "authorization_code",
        client_id: clientId,
        code,
        code_verifier: verifier,
        redirect_uri: redirectUri,
        ...(MAL_CLIENT_SECRET ? { client_secret: MAL_CLIENT_SECRET } : {}),
      });
      return tokensResult(outcome, "MAL");
    }
    if (provider === "kitsu") {
      const clientId = PROVIDERS.kitsu;
      if (!clientId) {
        return { payload: null, error: "Kitsu non configurato sul relay (manca KITSU_CLIENT_ID)" };
      }
      const form: Record<string, string> = {
        grant_type: "authorization_code",
        client_id: clientId,
        code,
        redirect_uri: redirectUri,
      };
      if (KITSU_CLIENT_SECRET) form.client_secret = KITSU_CLIENT_SECRET;
      const outcome = await tokenExchange(KITSU_TOKEN_URL, form);
      return tokensResult(outcome, "Kitsu");
    }
    if (provider === "anilist") {
      // AniList accetta SOLO body form-encoded sull'endpoint token (il JSON
      // non viene letto: risponde "unsupported_grant_type"). Il client_secret
      // va incluso SOLO per i client "confidential": i client pubblici
      // falliscono se il secret è presente, perciò l'env è opzionale.
      const clientId = PROVIDERS.anilist;
      if (!clientId) {
        return { payload: null, error: "AniList non configurato sul relay (manca ANILIST_CLIENT_ID)" };
      }
      const form: Record<string, string> = {
        grant_type: "authorization_code",
        client_id: clientId,
        code,
        redirect_uri: redirectUri,
      };
      if (ANILIST_CLIENT_SECRET) form.client_secret = ANILIST_CLIENT_SECRET;
      const outcome = await tokenExchange(ANILIST_TOKEN_URL, form);
      return tokensResult(outcome, "AniList");
    }
    return { payload: null, error: `provider sconosciuto: ${provider}` };
  } catch (err) {
    console.error("exchangeCode failed", err);
    return { payload: null, error: `errore di rete nello scambio: ${String(err)}` };
  }
}

function tokensResult(outcome: ExchangeOutcome, providerLabel: string): ExchangeResult {
  if (!outcome.ok) {
    return { payload: null, error: `${providerLabel} ha rifiutato lo scambio: ${outcome.error}` };
  }
  const payload = tokensJson(outcome.data);
  if (!payload) {
    return { payload: null, error: `${providerLabel} ha risposto senza access_token` };
  }
  return { payload, error: null };
}

async function tokenExchange(
  url: string,
  form: Record<string, string>,
): Promise<ExchangeOutcome> {
  const body = new URLSearchParams();
  for (const [k, v] of Object.entries(form)) body.set(k, v);
  const resp = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body,
  });
  const bodyText = await resp.text();
  if (!resp.ok) return { ok: false, error: describeError(resp, bodyText) };
  try {
    return { ok: true, data: JSON.parse(bodyText) as Record<string, unknown> };
  } catch {
    return { ok: false, error: `risposta non-JSON del provider (HTTP ${resp.status})` };
  }
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

function escHtml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
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
  baseUrl: string,
): Promise<string | null> {
  const redirectUri = `${baseUrl}/callback/${provider}`;
  session.state.redirectUriBase = baseUrl;
  // Un client ID vuoto (env non configurato sul relay) produce errori
  // incomprensibili lato provider (es. MAL "invalid_client"). Meglio un
  // errore chiaro subito, così il telefono mostra "non configurato".
  if (!clientId) return null;
  switch (provider) {
    case "mal": return malAuthorizeUrl(clientId, redirectUri, session);
    case "kitsu": return `${KITSU_AUTHORIZE_URL}?response_type=code&client_id=${encodeURIComponent(clientId)}&redirect_uri=${encodeURIComponent(redirectUri)}&state=${session.userCode}`;
    // AniList: solo Authorization Code Grant (response_type=code). L'implicit
    // grant (response_type=token) non è supportato da AniList: il provider
    // risponde "unsupported_grant_type".
    case "anilist": return `${ANILIST_AUTHORIZE_URL}?response_type=code&client_id=${encodeURIComponent(clientId)}&redirect_uri=${encodeURIComponent(redirectUri)}&state=${session.userCode}`;
    default: return null;
  }
}

// MyAnimeList: auth-code + PKCE. MAL accetta SOLO il metodo "plain":
// la code_challenge DEVE essere il code_verifier stesso (S256 -> "Failed
// to verify code_verifier"). Il code_verifier è generato e conservato
// nella sessione (server-side), poi usato nel codice → token.
async function malAuthorizeUrl(
  clientId: string,
  redirectUri: string,
  session: RelaySession,
): Promise<string> {
  const verifier = generateOauthValue(64);
  session.state.codeVerifier = verifier;
  session.state.state = session.userCode;
  return `${MAL_AUTHORIZE_URL}?response_type=code&client_id=${encodeURIComponent(clientId)}&code_challenge=${encodeURIComponent(verifier)}&code_challenge_method=plain&state=${session.userCode}&redirect_uri=${encodeURIComponent(redirectUri)}`;
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

// ============================================================
// Router
// ============================================================
function router(req: Request): Promise<Response> {
  const url = new URL(req.url);
  // Su Supabase il pathname arriva con il prefisso `/functions/v1/tracker-login`,
  // su Deno Deploy con `/tracker-login`; in entrambi i casi lo togliamo così il
  // router vede i percorsi "relativi" (start, poll, ...).
  const rawPath = url.pathname;
  const path = rawPath
    .replace(/^\/functions\/v1\/tracker-login/, "")
    .replace(/^\/tracker-login/, "");
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
