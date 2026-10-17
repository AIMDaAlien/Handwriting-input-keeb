# M4 Air setup

One command. Idempotent. Mirrors FUTO's CI image so "works in CI" and "works on the Air" mean the same thing.

```bash
curl -fsSL https://raw.githubusercontent.com/AIMDaAlien/Handwriting-input-keeb/claude/futo-handwritten-input-g1igu1/tools/mac/bootstrap.sh | bash
```

Or from a clone: `bash tools/mac/bootstrap.sh`. Read it first; it's 90 lines.

| It installs | Why this version |
|---|---|
| Temurin JDK 21 | FUTO CI uses `gradle:8.14.3-jdk21` |
| Android SDK platform 35, build-tools 35.0.0 | FUTO `compileSdk 35` |
| NDK 28.2.13676358, CMake 3.22.1 | pinned in FUTO `build.gradle`, native dictionary code |
| platform-tools (adb) | device survey, install, logcat |
| Python 3 + requests | FUTO's bundle-download task |
| git-lfs | the swipe model submodule lives on Hugging Face |
| Android Studio (cask) | optional GUI; everything works from the terminal |

**One SDK, not two.** The script installs packages into `~/Library/Android/sdk`, which is Android Studio's default. On first launch choose *Custom* and point the IDE at that path. If you let Studio create its own, you'll have two SDKs disagreeing about NDK versions within a week.

## 16 GB RAM notes

- FUTO sets `org.gradle.jvmargs=-Xmx2048m`. Leave it. Close the browser during the first native build.
- Prefer CI for full builds (`.github/workflows/build-futo.yml`). The Air does incremental builds and on-device debugging.
- Wireless debugging (Settings → Developer options → Wireless debugging, then `adb pair` / `adb connect`) frees the one cable.

## macOS 27

Checked 2026-10-17: Android Studio's known-issues page has no macOS 27 entry, and no Google statement either way was found. Translation: probably fine, unconfirmed. If Studio misbehaves, nothing in this project needs the IDE; Gradle from the terminal plus CI covers it.

## Xiaomi specifics

- Enable Developer options (tap *OS version* seven times), USB debugging, and **Install via USB**. Global HyperOS historically does not demand a Mi account for `adb install`; China ROMs do. Flagging as unverified for HyperOS 3.
- Revoke-and-reauthorize if the tablet shows `unauthorized` in `adb devices`.
- If USB adb flaps (the "USB debugging connected" notification toggles on every command), use
  Wireless debugging: Developer options → Wireless debugging → Pair device with pairing code, then
  `adb mdns services`, `adb pair <ip:pairing-port> <code>`, `adb connect <ip:connect-port>`.
  adb then lists the tablet twice; `export ANDROID_SERIAL=<ip:connect-port>`.
