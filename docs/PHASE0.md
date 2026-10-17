# Phase 0: fork, build, survey the device

Status as of 2026-10-17. Two columns because the work splits between a cloud box
with no Android SDK and your laptop with one.

| Step | Where | Status |
|---|---|---|
| Pin FUTO Keyboard source as `upstream/` submodule (GitHub mirror, tag `0.1.30`) | cloud | done |
| Recognizer core: interface, ink model, preprocessing, rasterizer, commit policy, serialization, unit tests | cloud | done, see `recognizer-core/` |
| Build FUTO unmodified (`assembleUnstableDebug`) | GitHub Actions or the M4 Air | workflow added (`.github/workflows/build-futo.yml`); first run pending. Not possible from the cloud container: Google's SDK host and FUTO's GitLab are unreachable there |
| M4 Air toolchain | **your machine** | `tools/mac/bootstrap.sh`, see `docs/MAC_SETUP.md` |
| Sideload to Pad 7 and run `scripts/phase0-device-check.sh` | **your machine** | pending |
| Gboard "Write in text fields" check | **your machine** | pending |

## 1. Build FUTO

Fastest path: push, wait for the **build** workflow, download the APK artifact from the
Actions tab onto the tablet, install. Or with the GitHub CLI on the Air:

```bash
gh run download -n futo-handwriting-unstable-debug-apk -D /tmp/apk && adb install -r /tmp/apk/*.apk
```

Local path on the Air (after `tools/mac/bootstrap.sh`):

```bash
# Android Studio Ladybug or newer, JDK 17+, NDK 28.2 (build.gradle pins it; the SDK manager will fetch it).
git clone --recursive https://github.com/AIMDaAlien/Handwriting-input-keeb.git
cd Handwriting-input-keeb/upstream
git submodule update --init --recursive      # pulls libs, translations, themes from gitlab.futo.org
                                             # and futo-swipe from huggingface.co
./gradlew assembleUnstableDebug
adb install -r build/outputs/apk/unstable/debug/*.apk
```

Then Settings → Additional settings → Languages & input → enable "FUTO Keyboard (unstable)".
If the swipe-model submodule fails, the keyboard still builds; swipe just degrades.

Expect the first build to take 10 to 25 minutes because of the native dictionary
code. Confirm the stock keyboard works before changing anything, so later
breakage is unambiguously ours.

## 2. Survey the tablet

```bash
scripts/phase0-device-check.sh | tee docs/device-survey.txt
```

What each section tells us:

- **Build.** Expect Android 16, API 36. If it says 15, the HyperOS 3 update has not landed yet and `setStylusHandwritingRegion` is off the table until it does.
- **`stylus_handwriting_enabled`.** This is the system switch Android 14 added under Settings → System → Stylus. If it prints `1` or `0`, the framework is present. If it prints nothing, Xiaomi may have removed the UI but the API can still exist; the Gboard test below settles it.
- **`dumpsys input_method`.** Lists every installed IME and whether it declares `supportsStylusHandwriting`. Gboard should say true, FUTO false (for now).
- **`getevent -il`.** Look for a device whose name contains "pen" or "stylus" with `ABS_PRESSURE`. That confirms the Focus Pen reports as `TOOL_TYPE_STYLUS` with pressure, which both input modes rely on for palm rejection.

## 3. The Gboard test (five minutes, decides Phase 3)

1. Install Gboard from the Play Store if absent.
2. Switch to it, open Gboard settings. Look for **Write in text fields**.
3. Tap a text field with the Focus Pen while Gboard is active.

| Result | Meaning |
|---|---|
| Menu exists and writing in the field works | HyperOS 3 keeps the Android 14+ stylus handwriting framework. Phase 3 (write directly in fields) is a go. |
| Menu exists but pen only moves the caret | Framework present, Gboard not enabled for this device. Phase 3 still probably works for us since we control the IME. |
| Menu absent | Xiaomi gutted or hid the framework. Phase 3 is at risk; Phase 1 (handwriting pad) is unaffected. |

Record the result in the table at the top of this file.

## 4. Verify the recognizer core on any machine

```bash
./gradlew :recognizer-core:test
```

No Android SDK needed. This is the module the Phase 1 FUTO code will import.
