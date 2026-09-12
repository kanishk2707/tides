# Deploying the match server

## What you need

- A Linux box with a public IP (a $6 VPS is plenty for a launch) and ports 80/443 open.
- A DNS A record, e.g. `play.yourdomain.com`, pointing at it.
- Docker with the compose plugin.
- Your Supabase project URL and **service-role secret** (`sb_secret_...`).

## Steps

```bash
git clone <your repo> aether && cd aether/deploy
cp .env.example .env
$EDITOR .env                      # DOMAIN, SUPABASE_URL, SUPABASE_SECRET_KEY
docker compose up -d --build
curl https://play.yourdomain.com/healthz
#  {"ok":true,"players":0,"rooms":0,"queued":0,"uptime":12}
```

Then set `Publish.SERVER_URL` in `core/.../Platform.kt` to `wss://play.yourdomain.com/ws` and
rebuild the app.

## Before this goes live

1. **Run the SQL.** Supabase dashboard → SQL Editor → paste `supabase/migrations/0001_profiles_and_matches.sql` → Run.
2. **Enable anonymous sign-ins.** Supabase dashboard → Authentication → Providers → *Anonymous* → enable.
   Without this the app cannot create accounts and online play fails at "SIGNING IN".
3. **Rotate the secret** if it has ever been pasted into a chat, an issue, or a screenshot.
   Settings → API → service role → rotate. Update `.env`, `docker compose up -d`.
4. **Abuse limits on Supabase.** Authentication → Rate Limits: anonymous sign-ins default to 30/hour/IP. Keep it.

## Operating it

- Logs: `docker compose logs -f server`
- Rooms and players: `curl -s localhost:7789/healthz` from the box, or `/metrics` for Prometheus.
- Restart with zero data loss: matches in progress are lost (they are in memory), profiles are not.
- Scale: one process handles a few hundred concurrent players. Beyond that, run a second box on
  a second hostname; there is no cross-server matchmaking yet.
- Upgrades: the app refuses servers on a different `PROTOCOL_VERSION`, so ship the server first,
  then the app.

## Without Docker

`deploy/aether-tides.service` runs the jar under systemd. Put Caddy or nginx in front for TLS,
or hand the server a PKCS12 keystore via `TLS_KEYSTORE` / `TLS_KEYSTORE_PASSWORD` and point
the app at `wss://host:7788` directly.
