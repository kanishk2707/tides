# Play Console — everything the forms will ask

This file is the answer key. Every question the Google Play Console asks during first
publication is here with the answer that matches what the app actually does. Copy, do not
improvise: the Data Safety form is compared against the app's real behaviour and a mismatch is
a policy strike.

## App details

| Field | Value |
|---|---|
| App name | Aether Tides |
| Default language | English (United States) |
| Category | Game → Action (alternatives: Arcade, Simulation) |
| Tags | Multiplayer, Physics, Sailing, PvP, Offline |
| Package name | `com.mythron.aethertides` |
| Contact email | polarizenterprises@gmail.com |
| Privacy policy URL | `https://kanishk2707.github.io/tides/privacy.html` (live once Pages is enabled on the repo) |
| Free / paid | Free |
| Contains ads | **No** |
| In-app purchases | **No** |

## Store listing copy

**Short description (80 chars max):**
> Sail a real physics sea. Or become the storm that stops the sailor. 1v1, two rounds.

**Full description:**
> ONE SAILS. ONE DROWNS THEM.
>
> Aether Tides is an asymmetric two-player sailing duel. The Navigator sails a ship across
> five leagues of open sea, surfing real Gerstner swell and shaping the water with four
> elemental workings. The Tempest is the sea itself: sowing reefs, mines, corsair flights
> and rogue waves into the lane ahead, and turning the storm dial that raises the sea for
> both of you.
>
> Every match is two rounds on the same sea with the sides swapped. The better run wins.
>
> • A ship that actually floats — buoyancy at eleven points along the keel, pitching,
>   launching, slamming and foundering with nothing scripted.
> • Waves that obey physics: long swell outruns short chop, and only a wave face can carry
>   you past hull speed.
> • Four workings that fuse into three more when you overlap them.
> • Nine hazards, a Kraken, and a storm dial for the other side.
> • Practice offline against the ship's own crew, on either side.
> • Anonymous accounts — no email, no password. Delete everything from Settings.
> • No ads. No purchases. Nothing to buy.

## Content rating questionnaire (IARC)

| Question | Answer |
|---|---|
| Violence — cartoon or fantasy violence | **Yes** (a ship is damaged by mines, sea creatures; no blood, no human injury depicted) |
| Realistic violence | No |
| Blood / gore | No |
| Sexual content / nudity | No |
| Profanity / crude humour | No |
| Drugs, alcohol, tobacco | No |
| Gambling / simulated gambling | No |
| Fear / horror themes | No (a Kraken appears; stylised) |
| User interaction — users can interact | **Yes** (matched 1v1; display names are visible) |
| User interaction — share location | No |
| User interaction — unrestricted internet | No |
| In-app purchases / digital goods | No |

Expected result: **PEGI 7 / ESRB E10+ / USK 6 / ClassInd L**.

## Target audience and content

- Target age group: **13–15, 16–17, 18 and over**. Do **not** tick "under 13".
- "Is your app designed for children?" → **No.**
- Not a "Teacher Approved" candidate; not a family app.

## Data Safety form

**Does your app collect or share any of the required user data types?** → Yes

**Is all of the user data collected by your app encrypted in transit?** → Yes

**Do you provide a way for users to request that their data is deleted?** → Yes
(in-app: Settings → Delete Account; also by email)

Then, per data type:

| Data type | Collected? | Shared? | Ephemeral? | Required? | Purposes |
|---|---|---|---|---|---|
| **App activity → App interactions** | Yes | No | No | Required for online play | Analytics, App functionality |
| **App info and performance → Crash logs** | Yes (only if Firebase is enabled) | No | No | Required | Analytics |
| **App info and performance → Diagnostics** | Yes (only if Firebase is enabled) | No | No | Required | Analytics |
| **Personal info → User IDs** | Yes | No | No | Required for online play | App functionality, Account management |
| **Personal info → Name** | Yes (display name, optional) | No | No | Optional | App functionality |
| **Device or other IDs** | Yes (only if Firebase is enabled — Firebase installation ID) | No | No | Required | Analytics |

Everything else: **not collected.** In particular: Location — No. Contacts — No. Photos/
videos — No. Audio — No. Financial info — No. Health — No. Messages — No. Web browsing — No.
Files — No. Calendar — No. Installed apps — No.

"Shared" is No throughout: Supabase and Firebase are service providers processing on your
behalf, which Play's definition excludes from "sharing".

If you ship **without** `google-services.json`, remove the three Firebase rows.

## Account deletion (required since 2023)

- In-app path: **Settings → Delete Account** (two-step confirmation).
- Web URL for the form: `https://kanishk2707.github.io/tides/delete-account.html`
- Data deleted: the auth user, profile, match history. Nothing retained except server logs
  (IP, up to 30 days) which are not linked to the account.

## App access

"All functionality is available without special access" → **No**, then explain:
> Online multiplayer creates an anonymous account automatically on first use; no credentials
> are needed. Practice mode is fully available without any account. Reviewers can test online
> play by tapping FIND A MATCH → QUICK MATCH; a practice opponent is assigned within 12
> seconds if no other player is queued.

## Advertising ID

"Does your app use advertising ID?" → **No.** (The manifest declares no `AD_ID` permission;
Firebase Analytics is configured without it. If Play's scanner flags the Firebase SDK, answer
that the ID is not used for advertising or analytics personalisation.)

## Government apps / Financial features / Health

All → No.

## Release

- Upload `android/build/outputs/bundle/release/android-release.aab` signed with your release
  key (see README → Signing). Play App Signing: **enrol** — let Play hold the app signing key
  and keep your upload key locally.
- First release to **Internal testing** with your own account, then **Closed testing** (Play
  now requires 12 testers for 14 days before a personal developer account can go to
  production), then Production.
- Countries: all, or your choice. Nothing in the app is region-specific.
