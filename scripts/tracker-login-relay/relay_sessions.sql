-- NuvioTV tracker-login relay: sessioni QR persistenti.
-- La edge function Supabase gira su piu' istanze: le sessioni
-- devono vivere nel database, non in memoria.

create table if not exists public.relay_sessions (
  user_code text primary key,
  provider text not null,
  status text not null default 'pending' check (status in ('pending', 'approved', 'expired')),
  payload text,
  state jsonb not null default '{}'::jsonb,
  expires_at bigint not null,
  poll_interval_seconds int not null default 3,
  created_at timestamptz not null default now()
);

create index if not exists relay_sessions_expires_at_idx
  on public.relay_sessions (expires_at);

alter table public.relay_sessions enable row level security;

-- Nessun accesso diretto: la edge function scrive/legge con la
-- service role key (bypassa RLS). Blocchiamo tutto in maniera esplicita.
drop policy if exists "no public access" on public.relay_sessions;
create policy "no public access"
  on public.relay_sessions for all
  using (false) with check (false);
