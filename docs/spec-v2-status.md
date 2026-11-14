# MaxGram: specification v2 and acceptance

The user's v2 specification supersedes the previous UI-base and login-language decisions:

- Requested Android base: NextAlone/Nagram, main commit `8f2a8a03aa7aec599150b09d302bfd5023283344` inspected on 2026-11-14. Migration is pending; existing builds still use the separately pinned DrKLO base.
- Login languages: Russian and English. The existing seven-language picker must be reduced during the UI migration.
- QR approval belongs in authenticated Settings → Devices. A new device displays a challenge; an authenticated device scans and approves it. The login-screen QR shortcut is to be removed.
- Remove unsupported controls instead of sending unsupported Telegram RPCs. Local ghost/unread/no-typing remain MAX transport policies.
- Android contacts, bot identity, presence, all media directions, formatting, internal links, MAX privacy/blacklist, profile navigation, automatic emoji animation, debug isolation, sounds and themes remain explicit acceptance items.
- Account settings: first name, last name, biography and server-backed account deletion/inactivity options only. Opcode names 199/200 exist in Komet's map, but their payload and semantics still require confirmation; never imitate deletion with a local preference.
- Opt-in deleted-message retention preserves messages received by this installation. It cannot recover messages deleted before receipt or while retention was disabled. Local delete, clear history and logout must have explicit purge behavior.

## Source review

Primary sources inspected (full commits are recorded in `integrations/max-session-profiles.json`):

- [PyMax session payloads](https://github.com/MaxApiTeam/PyMax/blob/c4cb917d354a1179be625925530d72c8aaa969ff/src/pymax/api/session/payloads.py): WEB handshake has `userAgent`/`deviceId`; mobile handshake also has `mt_instanceid` and `clientSessionId`. Mobile-only fields must not be blindly added to WEB payloads.
- [PyMax configuration](https://github.com/MaxApiTeam/PyMax/blob/c4cb917d354a1179be625925530d72c8aaa969ff/src/pymax/config.py): its ID generator uses `secrets.token_hex(8)` (16 hexadecimal characters). The example in the supplied specification contains 32 hexadecimal characters. The current official WEB implementation instead uses `crypto.randomUUID()`; therefore no universal hex-only rule is valid. Existing device IDs bound to tokens must be preserved, not reformatted automatically.
- [PyMax session restoration](https://github.com/MaxApiTeam/PyMax/blob/c4cb917d354a1179be625925530d72c8aaa969ff/src/pymax/app.py): saved device identity and instance ID are restored; regenerating them at every process launch is not a universal requirement.
- [PyMax version catalog](https://github.com/MaxApiTeam/PyMax/blob/c4cb917d354a1179be625925530d72c8aaa969ff/src/pymax/versions/catalog.py): mobile app version is coupled to a build fingerprint. Historical `25.12.14`/`0x97CB` are not a verified current default.
- [Komet spoofing service](https://github.com/KometTeam/Komet/blob/7bbb73bd2c05877e48b37f6ec6f096471a341404/lib/core/storage/spoofing_service.dart): per-account/pending profiles, version reset and device identity persistence. Komet and PyMax use different version recommendations; neither source proves ban immunity.
- `icyfalc0n/maxcalls`, `junioraww/maxplus`, `eizemazal/avenarius` and `pr0bel1230/max-api-docs` have been retrieved for API/call/media comparison. Official `max-messenger` repositories expose Bot API clients and are not a replacement for the user-account protocol.

The live official WEB assets reviewed on 2026-11-14 are `BWj-8FBy.js` (WEB version `26.11.6`, endpoint `wss://api.oneme.ru/websocket`) and `RmCTWSHK.js` (protocol version 11, WEB UUID generator, `WEBPUSH` push type, device locale separate from interface locale). Their hashes are recorded with the session catalog. The web local-storage `__oneme_auth` value is JSON containing `viewerId` and `token`; importing the whole JSON as a token is incorrect.

Current PyMax telemetry sends `Opcode.LOG` (5), not the guessed 1103 in the supplied text; it is disabled by default. Synthetic always-on `CHAT_LIST` telemetry is not a verified requirement and is not enabled.

Citation placeholders such as `[citation:13]` in the supplied text are not independently traceable sources. The claim about approximately 700 bans and any universal detection claim remain unverified.

## Publication gate

`Build MaxGram APK` creates prototype artifacts and cannot publish GitHub Releases. `Install prototype APK` tests the exact build artifact instead of downloading an older release.

`Publish verified MaxGram APK` is a separate workflow. It downloads the already tested APK and matching source without rebuilding them. `scripts/validate_release.py` requires the APK hash, source revision, session-profile hash, a current official-version review, every acceptance check and a completed observation window of at least 72 hours. Missing, future-dated, mismatched or ban-positive evidence blocks publication.

`docs/validation/acceptance.json` is intentionally pending. No live-account observation has been performed. A 72-hour successful observation is a release criterion, not a guarantee against a later restriction. Do not put tokens, phone numbers, verification codes or unredacted protocol frames in the evidence or public artifacts.

## Current known defect

The alpha.11 Android test detected insufficient Russian legal-footer spacing after a language switch. That build is a prototype; its successful compilation is not a completed UI acceptance test. The next artifact must resolve and retest this defect.

## Session-core implementation checkpoint

Wire-specific profile models now separate WEB metadata from mobile `buildNumber`/instance/session fields. New WEB identities follow the official UUID generator; the mobile helper uses 16 hexadecimal characters. Previously stored IDs are never normalized. Existing prototype sessions retain their legacy version and metadata instead of being silently converted to a new profile.

The Android adapter saves and restores its browser User-Agent. If none is stored, WebView initialization is posted to the main loop and the connection waits asynchronously. It no longer starts a restored session with a fabricated `MAXGRAM-CLIENT/...` browser header. One snapshot supplies both the HTTP User-Agent and INIT payload; closing while waiting for initialization fails pending requests. JVM tests cover delayed WebSocket upgrade/profile changes, closure before initialization, WEB/mobile field separation and identity preservation.

The profile settings UI, explicit apply/relogin flow, first-launch consent, token import and mobile TCP transport are still pending. The mobile payload model does not enable an Android/TCP login and must not be advertised as such. The legal-footer correction now reduces excess form gaps while keeping minimum separation; it still needs the next installed-APK test.
