# Continuation prompt for Claude Code on the M4 Air

Paste everything below the line into Claude Code, run from the repo root.

---

You are continuing a project with me (Aim). Read `README.md`, `docs/PHASE0.md`,
`docs/MAC_SETUP.md` first; they are current as of 2026-10-17.

**Project:** privacy-first stylus handwriting input for my Xiaomi Pad 7 (HyperOS 3,
Android 16 / API 36, Focus Pen), built as a personal fork of FUTO Keyboard.
Repo `AIMDaAlien/Handwriting-input-keeb`, work on branch
`claude/futo-handwritten-input-g1igu1`, never push elsewhere.

**State of the repo**
- `recognizer-core/`: pure-JVM Kotlin 2.1 module. `HandwritingRecognizer` interface,
  `Ink`/`InkStroke`/`InkPoint` + `StrokeBuilder`, `InkPreprocessor`, `StrokeRasterizer`,
  `CommitPolicy`, `InkSerialization`, `FakeRecognizer`, `CompositeRecognizer`. 25 tests,
  green locally and in GitHub Actions. `./gradlew :recognizer-core:test` needs no Android SDK.
- `upstream/`: FUTO Keyboard, git submodule pinned to tag `0.1.30` (GitHub mirror of
  gitlab.futo.org/keyboard/latinime). Unmodified so far. Its own submodules live on
  gitlab.futo.org and huggingface.co (futo-swipe, LFS).
- `.github/workflows/build-futo.yml`: job 1 runs recognizer-core tests (passing);
  job 2 builds `upstream` with `assembleUnstableDebug` and uploads
  `futo-handwriting-unstable-debug-apk`. First run (#1) was mid-build when I handed off:
  checkout, submodules, LFS, SDK, Python steps all succeeded. Check its result first:
  `gh run list --workflow build-futo.yml` and `gh run view --log-failed` if red.
- `tools/mac/bootstrap.sh` + `Brewfile`: I ran this and **some steps failed**. First task:
  ask me what failed, or re-run it and read the output, then fix the script so it is
  idempotent and correct, commit, push. Design intent: ONE Android SDK at
  `~/Library/Android/sdk` shared by Android Studio and the CLI; JDK 21; SDK platform 35,
  build-tools 35.0.0, NDK 28.2.13676358, CMake 3.22.1 (these mirror
  `upstream/.ci/Dockerfile`); Python 3 with `requests`; git-lfs.
- `scripts/phase0-device-check.sh`: read-only adb survey of the tablet. Not yet run.

**Phase 0 remaining (do in order)**
1. Fix the bootstrap; confirm `adb devices` sees the Pad 7 (Developer options, USB
   debugging, Install via USB; HyperOS global may or may not demand a Mi account, unverified).
2. Run `scripts/phase0-device-check.sh | tee docs/device-survey.txt`. Expect API 36.
   Record results in the table at the top of `docs/PHASE0.md`.
3. Get an unmodified FUTO APK onto the tablet (CI artifact via `gh run download`, or
   `cd upstream && ./gradlew assembleUnstableDebug`), install, confirm it types.
4. The Gboard test from `docs/PHASE0.md` §3: does "Write in text fields" exist and work
   with the Focus Pen? This decides whether Phase 3 (write directly into fields) is viable.

**Phase 1 (after Phase 0): handwriting pad as a FUTO Action window**
- Pattern to copy: `upstream/java/src/org/futo/inputmethod/latin/uix/actions/VoiceInputAction.kt`.
  An `Action(icon, name, windowImpl, persistentState)` whose `ActionWindow` commits text via
  `manager.createInputTransaction()` → `updatePartial()` live, `commit()` final, `cancel()`.
- Register in `uix/actions/Registry.kt` `AllActionsMap` as `"handwriting"`, APPEND at the end
  (indices must stay stable), add to `DefaultActionSettings` Favorites.
- Add `recognizer-core` as a Gradle dependency of the FUTO app (include the module from the
  outer `settings.gradle.kts`, or copy sources under `org.futo.inputmethod.latin.uix.handwriting`).
- Thin Android adapter: `MotionEvent` → `StrokeBuilder` (use `getHistoricalX/Y/EventTime/
  Pressure`, honour `TOOL_TYPE_STYLUS`, ignore `TOOL_TYPE_FINGER` while a stylus stroke is live).
- Compose `Canvas` pad + candidate strip + `CommitPolicy`-driven idle commit. Wire to
  `FakeRecognizer` first; the UI must work before any model exists.
- Models load from `context.filesDir`, never downloaded by the keyboard.

**Hard rules**
- `HandwritingRecognizer.recognize()` never touches the network. Missing model → throw
  `RecognizerException.ModelMissing`. This is the privacy guarantee; do not weaken it.
- FUTO is under the FUTO Source First License 1.1 (personal fork and free non-commercial
  redistribution OK, with a prominent "modified" notice). FUTO's README asks for no
  AI-generated PRs; this work stays in my fork, not upstreamed.
- MSIME (github.com/metasequoiaime/msime, PR #7281, merged 2026-10-12) has a GPL-3.0
  stylus-in-field implementation. Read it for Android API choreography; never copy code.
- Android stylus IME API facts: API 33 `onStartStylusHandwriting`/`onStylusHandwritingMotionEvent`/
  `getStylusHandwritingWindow` etc.; API 34 gestures + idle timeout; API 35 connectionless;
  API 36 `setStylusHandwritingRegion(Region)`; API 37 no InputMethodService changes.
  FUTO's `java/res/xml/method.xml` does not declare `supportsStylusHandwriting` yet.
- Commit style: clear messages, no model identifiers in commits or code. Push to the branch
  above after each coherent step. Don't open PRs unless I ask.
- Be skeptical, flag uncertainty, give confidence levels, keep explanations short with
  tables/diagrams over prose.

Start by asking me what failed in the bootstrap run, unless the output is already in front of you.
