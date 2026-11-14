<div align="center">

# MAXGRAM CLIENT

Specification v2 is in progress: see [implementation and acceptance status](docs/spec-v2-status.md). APKs remain prototypes. Automatic release publication is disabled; publication requires matching live-account acceptance evidence and a completed 72-hour observation. Neither a session profile nor a successful observation guarantees immunity from later account restrictions.

**The MAX messenger. The Telegram experience.**

Unofficial Android client built on [official Telegram Android](https://github.com/DrKLO/Telegram), with a native MAX backend based on [Komet](https://github.com/KometTeam/Komet).

[Download APK](https://github.com/Lubara7/MAXGRAM-CLIENT/releases) · [Source code](https://github.com/Lubara7/MAXGRAM-CLIENT) · [Build guide](docs/telegram-migration.md)

</div>

NagramXF is a reference for selected privacy behavior only; its application and extra menus are not compiled.

## Android client

Telegram-style navigation, chat list and message bubbles, connected to MAX. MAX QR sign-in uses a server-issued link and device confirmation. Phone-number sign-in supports CAPTCHA, a verification code, an account password and server-selected outgoing-call verification. The login screen includes language selection, help and MAX legal documents.

**Languages:** Русский · English · Español · Português (Brasil) · O‘zbek · Français · Беларуская.

**Android 8.1+ · ARM64 · Experimental alpha.** Live-account compatibility is still being tested. Media uploads, stories, messenger voice/video calls and background MAX push are not implemented. Login translations are bundled; the wider Telegram interface still has English fallback strings. See [release notes](docs/release-notes.md).

## Build from source

Requires JDK 21, Android SDK 36, Build Tools 36.0.0, NDK 27.2.12479018 and CMake 3.31.6.

```bash
git clone https://github.com/Lubara7/MAXGRAM-CLIENT.git
cd MAXGRAM-CLIENT
git submodule update --init --depth 1 upstream/telegram
python3 scripts/prepare_telegram_client.py
python3 scripts/init_telegram_dependencies.py
cd build/telegram-client
NATIVE_TARGET=arm64-v8a ./gradlew :tgmax-backend:test :TMessagesProj_AppStandalone:assembleAfatDebug
```

The prepared checkout uses real Telegram UI components and a Java MAX protocol adapter. MTProto transport is excluded from the native build. The Android application ID stays `io.github.lubara7.tgmax`, so updates preserve the installed application. CI reuses the alpha signing key.

## Development

- [Architecture and integration](docs/telegram-migration.md)
- [Authentication research and screen mapping](docs/auth-research.md)
- [Telegram / Nagram video screen inventory](docs/ui-video-reference.md)
- [Source provenance](SOURCE_PROVENANCE.md)
- [Release notes and limitations](docs/release-notes.md)

Backend tests cover binary frames, compression, CAPTCHA ordering, cancellation, OTP, call verification, sessions and stable message identifiers. CI also builds the APK, checks its signature and opens the published build in an Android emulator.

## License

Our integration code is distributed under [GNU GPL v3](LICENSE). Telegram, NagramXF and Komet notices remain in their source files. MAX artwork extracted from the user-supplied application retains its original ownership; provenance and hashes are recorded in [max-apk-assets.json](integrations/telegram/max-apk-assets.json).

MAXGRAM CLIENT is an independent project and is not affiliated with Telegram or MAX.
