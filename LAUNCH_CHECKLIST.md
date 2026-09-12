# Launch checklist

Everything between "it builds" and "it is on the Play Store", in the order to do it. Each
item says who has to do it: **you** (needs your accounts or a decision) or **done** (already
in the repo).

## 1. Secrets and keys

- [ ] **you** — Rotate the Supabase service-role key. It was pasted into a chat, which means it
      is compromised by definition. Supabase dashboard → Project Settings → API → *Rotate*.
- [ ] **you** — Put the new `sb_secret_…` in `deploy/.env` **only**. It is read by the match
      server from the environment and is never built into the app. `.env` is git-ignored.
- [x] **done** — The publishable key (`sb_publishable_…`) is in `core/…/Platform.kt`. It is
      designed to ship in the client; it can only do what Row Level Security allows, which
      here is: create anonymous sessions and read your own row.
- [x] **done** — `Publish.SUPABASE_URL` is set to `https://kldficmkpzgkhfwcforh.supabase.co`.
- [ ] **you** — Generate a release keystore and `keystore.properties` (README → Signing).
      Back the keystore up somewhere that is not this machine. Losing it means you can never
      update the app.

## 2. Supabase

- [x] **done** — The migration has been run: profiles, match_results, RLS and the leaderboard
      view exist.
- [x] **done** — Anonymous sign-ins are enabled (verified live: a real anonymous user was
      created against the project).
- [x] **done** — Anonymous sign-in rate limit set to 30/hour/IP.
- [ ] **you** — Optional: Database → Extensions → enable `pg_cron`, then run the retention
      schedule at the bottom of the migration file so match history is pruned at 12 months,
      which is what the privacy policy promises.

## 3. Match server

Only needed for **online** play; practice mode works with none of this.

- [ ] **you** — Follow `deploy/HOSTING.md`. It is written for someone who has never deployed
      anything: Fly.io, no VPS to buy, no domain to register, no DNS to configure, TLS
      automatic. About fifteen minutes, and it ends with a `wss://…fly.dev` hostname.
- [ ] **you** — Put that hostname in `Publish.SERVER_URL` (`core/…/client/Platform.kt`) and
      rebuild. Keep the `wss://` scheme.
- [x] **done** — `deploy/fly.toml` is configured (machines never sleep, health check on its
      own port, secrets by `fly secrets set`). `deploy/README.md` still has the VPS +
      Docker + Caddy path if you would rather own the box.
- [x] **done** — TLS, frame-size caps, per-IP and total connection caps, NaN rejection, name
      sanitisation, private-code minimum length, auth-before-anything, server-owned ratings,
      isolated room failures, single-writer sim thread.

## 4. Legal

- [x] **done** — The legal site is rendered into `docs/` (`python legal/build-site.py`) and
      the app links to `https://kanishk2707.github.io/tides/…`.
- [x] **done** — GitHub Pages is enabled on `kanishk2707/tides` (branch main, folder /docs)
      and live: https://kanishk2707.github.io/tides/privacy.html, …/terms.html,
      …/delete-account.html. Paste the privacy URL into Play Console → Store listing, and the
      delete-account URL into Data safety → Account deletion.
- [ ] **you** — In `legal/terms-of-service.md` §10, replace `[JURISDICTION]` with where
      Polariz Enterprises is established, then `python legal/build-site.py` and commit.
- [x] **done** — Contact address is `polarizenterprises@gmail.com` in the app and every document.
- [x] **done** — First-launch consent screen (terms, privacy, 13+ gate), in-app deletion,
      in-app licence list, legal version gating so a policy change re-prompts.

## 5. Firebase

- [x] **done** — `android/google-services.json` is installed (git-ignored) for the Firebase
      Android app `com.polariz.aethertides` in project `tide-6787a`. Crashlytics and Analytics
      compile in and are verified present in the APK.
- [x] **done** — The app's `applicationId` was changed from `com.mythron.aethertides` to
      `com.polariz.aethertides` to match, and the Kotlin package with it. This had to happen
      before the first Play upload: `applicationId` is permanent afterwards.
- [x] **done** — Debug builds carry a `.debug` suffix that Firebase does not know about, so
      the debug config is derived from the real one at build time. No second Firebase app to
      register. Debug builds never report — collection is off when `BuildConfig.DEBUG`.
- [ ] **you** — Keep the three Firebase rows in the Data Safety form (`legal/PLAY_CONSOLE.md`),
      since Firebase is now enabled.

## 6. Build and test

- [x] **done** — `./gradlew :shared:test` → 21 tests, physics + netcode + hostile-client.
- [ ] **you** — Bump `appVersionCode` / `appVersionName` in `gradle.properties`.
- [ ] **you** — `./gradlew :android:bundleRelease` → `android/build/outputs/bundle/release/android-release.aab`.
- [ ] **you** — Install the release build on a real phone (`bundletool` or a release APK via
      `assembleRelease`) and play one full online match against a second device or a friend.
      The debug build cannot stand in: it allows cleartext to the dev server, release does not.
- [ ] **you** — On the phone: Settings → Performance Readout → ON, play a stormy round, and
      note the worst-frame number. Under 34 ms is fine.

## 7. Play Console

- [ ] **you** — Developer account ($25 once). Identity verification takes a few days; start now.
- [ ] **you** — Create the app; fill every form from `legal/PLAY_CONSOLE.md` verbatim.
- [ ] **you** — Store assets you still need to make: 512×512 icon PNG (export the vector from
      Android Studio: right-click `ic_launcher` → *Export*), a 1024×500 feature graphic, and at
      least 2 phone screenshots — the ones in this conversation's scratchpad at 1440×720 are
      usable if you re-capture at your phone's resolution with the readout off.
- [ ] **you** — Internal testing → Closed testing (12 testers, 14 days — a Play requirement
      for personal accounts opened after Nov 2023) → Production.

## 8. After launch

- Watch `/metrics` for `aether_auth_failures_total` climbing (a bad client build or an attack)
  and `aether_rejected_full_total` (time to add a second box).
- Watch Supabase → Authentication → Users for the anonymous-user count; that is your real DAU.
- The protocol version gate means shipping the server first, then the app. Never the reverse.

## What is deliberately not here

- **No ads, no IAP.** Adding either later changes the Data Safety answers and the privacy
  policy; do them together.
- **No account linking (Google sign-in).** Anonymous accounts are lost on reinstall. Supabase
  supports linking an anonymous user to Google with one call if you want it later; the
  server side needs no change.
- **No cross-server matchmaking.** One server, a few hundred concurrent players. Enough to
  find out if anyone wants to play.
