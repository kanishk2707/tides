-- AETHER TIDES -- player profiles and match history
--
-- Run this in the Supabase SQL editor (or `supabase db push`). It is idempotent.
--
-- Trust model, in one paragraph: clients authenticate with Supabase Auth and get a JWT. They
-- present that JWT to the *game server*, which verifies it and then reads and writes these
-- tables using the service-role secret. Clients never touch these tables directly, so every
-- policy below is deny-by-default and the only grant is a read of your own row for future
-- use. Ratings and results can only change because the game server said so.

-- ---------------------------------------------------------------------------
-- profiles: one row per auth user, created by the game server on first sight
-- ---------------------------------------------------------------------------
create table if not exists public.profiles (
    id              uuid primary key references auth.users (id) on delete cascade,
    display_name    text not null default 'Sailor'
                    check (char_length(display_name) between 2 and 18),
    rating          integer not null default 1200 check (rating between 100 and 4000),
    matches_played  integer not null default 0 check (matches_played >= 0),
    matches_won     integer not null default 0 check (matches_won >= 0),
    best_distance   real not null default 0,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    last_seen_at    timestamptz not null default now()
);

comment on table public.profiles is
    'Server-owned player record. Written only by the match server with the service role.';

create index if not exists profiles_rating_idx on public.profiles (rating desc);

-- ---------------------------------------------------------------------------
-- match_results: one row per player per completed series
-- ---------------------------------------------------------------------------
create table if not exists public.match_results (
    id            bigint generated always as identity primary key,
    player_id     uuid not null references public.profiles (id) on delete cascade,
    opponent_id   uuid references public.profiles (id) on delete set null,
    won           boolean not null,
    rating_after  integer not null,
    distance      real not null default 0,
    duration      real not null default 0,
    seed          bigint not null,
    played_at     timestamptz not null default now()
);

create index if not exists match_results_player_idx on public.match_results (player_id, played_at desc);

-- ---------------------------------------------------------------------------
-- housekeeping: keep updated_at honest
-- ---------------------------------------------------------------------------
create or replace function public.touch_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

drop trigger if exists profiles_touch on public.profiles;
create trigger profiles_touch
    before update on public.profiles
    for each row execute function public.touch_updated_at();

-- ---------------------------------------------------------------------------
-- row level security: deny by default; players may read their own profile
-- ---------------------------------------------------------------------------
alter table public.profiles enable row level security;
alter table public.match_results enable row level security;

drop policy if exists "profiles: read own" on public.profiles;
create policy "profiles: read own"
    on public.profiles for select
    to authenticated
    using (auth.uid() = id);

drop policy if exists "match_results: read own" on public.match_results;
create policy "match_results: read own"
    on public.match_results for select
    to authenticated
    using (auth.uid() = player_id);

-- No insert/update/delete policies for anon or authenticated: those roles cannot write.
-- The service role bypasses RLS, which is how the match server operates.

-- ---------------------------------------------------------------------------
-- leaderboard view: public, but only what a scoreboard needs
-- ---------------------------------------------------------------------------
create or replace view public.leaderboard
with (security_invoker = false) as
    select display_name, rating, matches_played, matches_won
    from public.profiles
    where matches_played >= 3
    order by rating desc
    limit 100;

grant select on public.leaderboard to anon, authenticated;

-- ---------------------------------------------------------------------------
-- retention: match rows older than a year are not needed for anything
-- ---------------------------------------------------------------------------
-- Enable pg_cron in Database > Extensions, then schedule:
--   select cron.schedule('aether-prune', '0 4 * * *',
--     $$delete from public.match_results where played_at < now() - interval '365 days'$$);
