# AETHER TIDES

**An asymmetric two-player sailing game for Android.** One player sails a ship across five
leagues of open sea. The other player *is* the sea.

Built in Kotlin on libGDX. No game engine editor, no binary art assets, and no scripted
animation — the boat moves because a rigid-body solver says it does.

---

## The game

### Two sides, one course

| | **NAVIGATOR** | **TEMPEST** |
|---|---|---|
| Goal | Reach the shore, 2 600 m away | Stop them |
| Resource | Aether, regenerating faster while surfing | Malice, regenerating faster while hurting them |
| Tools | Four workings that reshape the water | Nine hazards sown into the lane ahead |
| Ultimate | Three fusions, made by overlapping two workings | Fury, which raises the Kraken |

A match is **two rounds on the same seed with the sides swapped**, and the better navigator run
takes it. That format is the answer to the usual problem with asymmetric games: both players
sail exactly the same sea against exactly the same opposition, so the result measures the
players rather than the balance of the two roles.

### The Navigator

The left thumb is the helm — up for sail, down for reefed, left and right to move crew weight
fore and aft. Weight aft on a rising face launches her off the crest; weight forward lands her
flat. Carry full sail in too much wind and the mast goes. Take water aboard through breaches or
over the rail and she sits lower, handles worse, and eventually founders.

The four workings are deliberately non-overlapping in role:

| | | |
|---|---|---|
| **GALE** | displacement | Throws loose hazards clear. Cast astern it fills the sail — the movement tool. |
| **MIST** | denial | Flattens the sea beneath it, drowns skimmers, slows everything. |
| **VOID** | absorption | Swallows mines and shot. The only thing that lifts a reef head off the seabed. |
| **BOLT** | removal | Chains between targets. Kills corsairs, cooks tentacles off the hull. |

Land two so their circles overlap before either expires and they collapse into something else:
**Mist + Bolt** is a holding field, **Gale + Void** is a lance driven downrange, **Gale + Mist**
raises a wave of your own that is the fastest water in the game if you can catch its face.

### The Tempest

You are not chasing anyone — you are authoring the water they have not reached yet. Everything
must be placed inside a marked band ahead of their bow, so every hazard is telegraphed and
every answer is possible. Reef, mine, floe, corsair flight, maelstrom, tentacle, rogue wave,
squall, and a snare that binds one of their four workings for eight seconds.

The storm dial is the interesting one: it raises the sea state for **both** players and costs
upkeep to hold. Rough water hurts a navigator — and it is also the only water worth surfing.

---

## Why the water behaves the way it does

The sea is a sum of seven Gerstner (trochoidal) components, each obeying the deep-water
dispersion relation `ω = √(gk)`. That single detail is most of what makes it read as real: long
swell visibly outruns short chop instead of everything sliding at one fake speed. The surface
is parametric, so sampling a height at a world *x* means inverting it — three fixed-point
iterations, with total steepness capped below the breaking limit so the water never turns
inside out.

The hull is not a sprite that follows a water line. Eleven stations along the keel each sample
the surface, work out how deep they are, and push up with the weight of the water they
displace. Everything else falls out of that:

- she pitches bow-up climbing a face, because the bow is lifted before the stern
- she launches off a steep crest, and the crew can trim her attitude in the air
- she lands badly and takes slam damage if the bow is down
- bilge water is real mass, so a leaking hull rides lower and handles worse

**Buoyancy acts along the local surface normal, not straight up.** On a slope that gives a
forward force of `g·sin θ`. The physics test measures 3.1 m/s² on a wave face *with the sail
furled* — that force is the entire reason surfing exists, and the first version of this code
applied buoyancy vertically and wondered why the boat could not surf.

A displacement hull cannot outrun her own bow wave; wave-making resistance bites hard past
about 20 knots. The only way past it is to catch a face and let the water do the work — which
is why the Tempest raising the sea is a genuine risk, and why Gale-astern is a *sailing*
decision rather than a targeting one.

---

## Architecture

```
shared/    Pure-JVM deterministic simulation + wire protocol.  No libGDX, no Android.
core/      libGDX client: renderers, HUDs, netcode, account, screens.
android/   Play Store target. Optional Firebase when google-services.json is present.
desktop/   Development harness (fast iteration, screenshot capture, online boot).
server/    Headless authoritative match server. Supabase for identity and profiles.
supabase/  Database migration (profiles, match history, RLS).
deploy/    Dockerfile, compose, Caddy TLS, systemd unit, env template.
legal/     Privacy policy, terms, deletion page, Play Console answers, site generator.
docs/      The rendered legal site, served by GitHub Pages from this folder.

LAUNCH_CHECKLIST.md is the ordered list of what is left to ship.
```

`shared` is the important one. The **server and every client run the same code**, so a client
can step the world forward from the last confirmed snapshot to hide latency and land on the
same answer the server does. `SimulationTest` asserts that parity holds exactly over 900 ticks.

### Netcode

- Authoritative server at a fixed **60 Hz**; snapshots out at **20 Hz**.
- Binary wire format, hand-rolled. A full snapshot of a busy match is **~350 bytes** — about
  7 KB/s down. The same state as JSON is roughly eight times that.
- Clients keep two frames and interpolate between them, ~100 ms behind, matching entities by id
  so a recycled pool slot never teleports something across the screen.
- The navigator additionally **predicts** their own hull forward from local input and replays
  unacknowledged inputs on each snapshot, carrying any residual error as a visual offset that
  bleeds off over about a fifth of a second.
- Clients send *inputs and intents only*. Every cost, cooldown, range and legality rule is
  evaluated server-side, so a modified client can ask for a free Kraken and simply be ignored.

Offline practice runs a complete server in-process and ships snapshots to itself **through the
same binary codec**. Single-player is therefore a genuine rehearsal for multiplayer, and the
netcode is exercised every time anybody plays at all.

### Identity and trust

Playing online creates an **anonymous account** through Supabase Auth: a random user id and a
token pair, no email, no password, no form. The client presents the access token in its first
frame; the match server verifies it with the auth service (one HTTPS call, cached for the
token's lifetime) and only then loads or creates the profile. Names are sanitised server-side,
ratings move by Elo only after a rated series between two humans, and every write to the
database is made by the server with the service-role secret. Clients cannot write at all.

The secret lives in the server's environment and nowhere else. The publishable key ships in
the app, as designed; Row Level Security limits it to creating sessions and reading your own
row. Account deletion is in-app (Settings → Delete Account) and removes the auth user, the
profile and the match history through foreign keys.

### Hardening

Everything a client sends is checked at the socket: protocol version, finiteness of every
float (NaN survives every clamp and would poison the sea for both players), frame size
(4 KB cap, enforced by the websocket draft before allocation), display-name character set,
private-code length, and an 8/s command budget. Total and per-IP connection caps refuse
before a Player object exists. One simulation thread is the only writer of match state;
socket callbacks and identity results are queued to it. A room that throws is closed on its
own; the others keep ticking. `SecurityTest` covers the shared half of all of this.

Release builds forbid cleartext (`network_security_config.xml`); the debug build allows it to
the emulator loopback only. TLS terminates at Caddy in the shipped deployment, or the server
takes a PKCS12 keystore directly.

### Rendering

Everything is generated at launch — there is not one raster image in the repository. Glows,
sparks, foam and cloud bodies are written into pixmaps at startup; the app icon is vector; the
only binary assets in the whole project are three open-licence fonts.

The sea is sampled on the CPU across the visible span and emitted as a colour-per-vertex
triangle strip four rows deep. That is what buys the look: one continuous surface that is cold
in the trough, translucent where light comes through the back of a crest, and white where it is
breaking — with no texture and no shader. The renderer samples the *same* `Ocean` the physics
used, so the hull is never pasted onto a decorative wave.

---

## Building

Requires JDK 17 and the Android SDK. Nothing else — Gradle fetches the rest.

```bash
# Play Store bundle
./gradlew :android:bundleRelease        # → android/build/outputs/bundle/release/

# APK for a device
./gradlew :android:assembleDebug
adb install -r android/build/outputs/apk/debug/android-debug.apk

# Match server (guest mode; see deploy/README.md for production with identity)
./gradlew :server:fatJar
java -jar server/build/libs/aether-tides-server-1.0.0.jar 7788
curl http://127.0.0.1:7789/healthz

# Development harness (desktop, no device needed)
./gradlew :desktop:run
./gradlew :desktop:run --args="--role nav"            # straight into a match
./gradlew :desktop:run --args="--role tempest --shot out --frames 600,1800"
./gradlew :desktop:run --args="--online ws://127.0.0.1:7788 --name Alder"   # queue immediately

# The physics and netcode test suite
./gradlew :shared:test -i
```

### Signing for release

Create `keystore.properties` in the project root (git-ignored):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Without it, `assembleRelease` falls back to the debug key so a fresh clone still builds.

### Pointing the client at a server

Production values live in `core/…/Platform.kt` (`Publish`): the Supabase URL and publishable
key, the `wss://` match server, and the legal URLs. Debug builds default to
`ws://10.0.2.2:7788`, the emulator's alias for the host machine, and expose a *Dev server*
setting; release builds ignore that setting unless it is `wss://`.

---

## Test suite

`./gradlew :shared:test -i` runs twenty-one tests over the simulation, the wire format and
the hostile-client cases, and prints the numbers it measured. They are written as *claims about the physics*, not as
regression guards:

```
sea state 0.22 -> peak-to-trough  1.27 m (Hs 0.86 m)
sea state 1.00 -> peak-to-trough 12.66 m (Hs 8.22 m)
calm water, full sail: 10.81 m/s (21.0 kn)
best forward acceleration on a face, sail furled: 3.12 m/s^2
storm run: peak 19.5 m/s, best surf 1.00, dominant swell 10.2 m/s
snapshot: 357 bytes with 11 entities -> 7.0 KB/s
prediction parity after 900 ticks: x=98.3557
bot series: navigator 7 - 3 tempest
```

The last line is the balance check: bot against bot at equal skill, over ten seeds, neither side
may be a walkover. The outcome is very sensitive to `Config.MALICE_REGEN` — roughly 8–2 to the
navigator at 4.7 and 1–9 at 5.4 — so the playable band is narrow and the shipped value sits in
the middle of it. `MatchStats.causeReport()` breaks damage down by source, which is the dial you
actually read when balancing.

---

## Status and what is not done

Playable end to end. Verified: the test suite passes; the debug APK installs and runs on the
Android emulator (API 36) with menu, practice match, touch helm and spell casting all working
and no exceptions in logcat; and a live server paired two separate clients into one match
(`room 3: Brine vs Corvid`) with snapshots flowing both ways.

**Not verified on physical hardware** — only on the emulator, which uses a software GL
renderer. Frame pacing and thermals on a real phone are untested.

Other known gaps:

- **Not yet pointed at your infrastructure.** `LAUNCH_CHECKLIST.md` lists the values to
  fill in and the dashboard switches to flip. Until then, online play runs in guest mode
  against a local server only.
- **No audio.** The event system (`EventKind`) already carries everything a sound bank would
  need; nothing is wired to it.
- **No IAP or ads.** Deliberately. Nothing in the design is built around them.
- **No account linking.** Anonymous accounts do not survive a reinstall. Supabase can link an
  anonymous user to Google sign-in later without any server change.
- **Snapshots are full, not delta-encoded.** At 7 KB/s this has not been worth doing; it is the
  obvious next optimisation if entity counts rise.
- **Balance is bot-derived.** Ten seeds of AI-vs-AI is a sanity check, not playtesting.

### Licences

Fonts are Cinzel Decorative and Rajdhani, both under the SIL Open Font License 1.1 — see
`android/src/main/assets/fonts/LICENSES.txt`. Everything else here is original.
