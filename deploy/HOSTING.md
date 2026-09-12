# Putting the match server online

You need one thing running on the internet for online play to work: the match server. This
guide assumes you have never done this before. Everything is copy-paste.

**Practice mode does not need any of this.** If you only want to ship the single-player game,
skip this file entirely — the app already works offline.

---

## Which path to take

| | **Fly.io** (recommended) | **A VPS** |
|---|---|---|
| Buy a server? | No | Yes, ~$5/month |
| Buy a domain? | No — you get `yourapp.fly.dev` | Yes, ~$10/year |
| Set up DNS? | No | Yes |
| Set up HTTPS? | No — automatic | Yes (Caddy does it, but you configure it) |
| Payment method needed? | **Yes**, even for the free allowance | Yes |
| Time | ~15 minutes | ~45 minutes |

Take the Fly path. The VPS path is in `deploy/README.md` if you would rather own the box.

---

## The Fly.io path, step by step

### 1. Install the Fly command line tool

Open **PowerShell** (not Git Bash) and run:

```powershell
iwr https://fly.io/install.ps1 -useb | iex
```

Close PowerShell and open a new one so the `fly` command is on your path. Check it:

```powershell
fly version
```

### 2. Make an account

```powershell
fly auth signup
```

A browser opens. Sign up (GitHub login is fine). Fly asks for a card — it will not charge you
for an app this small, but it will not let you deploy without one on file. If that is a
blocker, use the VPS path instead.

### 3. Create the app

From the project folder:

```powershell
cd C:\Users\Admin\projects\g\deploy
fly launch --no-deploy --copy-config --name aether-tides-YOURNAME
```

Replace `YOURNAME` with anything — the name has to be unique across all of Fly, and it becomes
your hostname. If it says the name is taken, pick another.

When it asks **"Would you like to tweak these settings before proceeding?"** answer **No**.
It reads `fly.toml`, which is already configured correctly.

### 4. Give it your secrets

These are the two values from Supabase. They are stored by Fly, encrypted, and never appear in
your code or in git:

```powershell
fly secrets set SUPABASE_URL=https://kldficmkpzgkhfwcforh.supabase.co
fly secrets set SUPABASE_SECRET_KEY=sb_secret_PASTE_THE_ROTATED_ONE_HERE
```

> Use the **rotated** service-role key. The one pasted into a chat earlier must be replaced
> first: Supabase dashboard → Project Settings → API → service_role → *Rotate*.

### 5. Deploy

```powershell
fly deploy
```

The first deploy takes a few minutes because it builds the whole project inside a container.
When it finishes it prints your hostname, something like `aether-tides-yourname.fly.dev`.

### 6. Check it is alive

```powershell
curl https://aether-tides-YOURNAME.fly.dev/healthz
```

You want:

```json
{"ok":true,"players":0,"rooms":0,"queued":0,"uptime":12}
```

If you get that, the server is running and reachable over TLS. You are done with hosting.

### 7. Point the app at it

Open `core/src/main/kotlin/com/polariz/aethertides/client/Platform.kt` and change one line:

```kotlin
const val SERVER_URL = "wss://aether-tides-YOURNAME.fly.dev"
```

Note **`wss://`**, not `https://` — it is a websocket. There is no `/ws` on the end for Fly
(that path only exists in the Caddy/VPS setup).

Then rebuild:

```
./gradlew --no-daemon :android:assembleDebug
```

and install it on your phone. Online play now works.

---

## Running it

```powershell
fly logs                      # live server log; you will see "room 1: Alder vs Brine"
fly status                    # is it up, which region, how many machines
fly apps restart aether-tides-YOURNAME
fly scale memory 2048         # if you ever run out of memory
```

Metrics for a monitoring tool are at `/metrics` in Prometheus format.

### What it costs

One `shared-cpu-1x` machine with 1 GB of memory, always on. At the time of writing that sits
around **$2–4/month**, and Fly's free allowance may cover it entirely. A few hundred concurrent
players fit on that one machine.

### When to add a second machine

Watch `/metrics`:

- `aether_rejected_full_total` climbing → you are hitting `MAX_PLAYERS`; raise it, or scale.
- `aether_auth_failures_total` climbing → either an out-of-date client build, or someone
  probing you.

Note that a second machine does **not** give you more matchmaking — there is no cross-server
pairing, so two machines are two separate player pools. Scale up (`fly scale memory`) before
scaling out.

---

## Upgrading later

The client refuses to talk to a server running a different `PROTOCOL_VERSION`, which means:

**deploy the server first, then ship the app.** Never the other way round — an app update that
reaches players before the server does will simply fail to connect.

```powershell
cd deploy
fly deploy
```

---

## If something goes wrong

**`fly deploy` fails while building.** Run `./gradlew --no-daemon :server:fatJar` locally
first; if that fails, the problem is the code, not Fly.

**`/healthz` returns nothing.** `fly logs` — look for the startup line. If it says
`auth=OFF`, your secrets did not get set; re-run step 4 and `fly deploy` again.

**The app says "SIGN-IN REJECTED".** The server could not verify the token with Supabase.
Check `SUPABASE_URL` has no trailing slash and that the secret is the *service-role* key, not
the publishable one.

**The app says "CONNECTION LOST" immediately.** Almost always `SERVER_URL` — check it starts
with `wss://` and matches the hostname `fly status` prints.

**The app says "UPDATE REQUIRED".** Server and app disagree on `PROTOCOL_VERSION`. Deploy the
server, then rebuild the app.
