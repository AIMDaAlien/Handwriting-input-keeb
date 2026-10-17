# Handwriting input for FUTO Keyboard (Xiaomi Pad 7 + Focus Pen)

> Planning document, 2026-10-17. Nothing here is built yet. This is the design and
> the order of work. Each phase has a confidence level and the research behind it.

## 1. What we're building

A **privacy-first stylus handwriting input** that lives inside a personal fork of
[FUTO Keyboard](https://github.com/futo-org/android-keyboard), targeting:

| Thing | Value |
|---|---|
| Device | Xiaomi Pad 7, HyperOS 2 (Android 15 / API 35) |
| Pen | Xiaomi Focus Pen (Bluetooth, ~8192 pressure levels) |
| Base | FUTO Keyboard, `LatinIME.kt`, Kotlin + Jetpack Compose, compileSdk 35, minSdk 24 |
| Script | Latin / English first (CJK via Zinnia later, see §4) |
| Hard rule | No ink leaves the device. No network at recognition time. |

Two user-facing modes, built in this order:

```
 MODE A: "Handwriting pad"  (any Android version, any app)
 ┌─────────────────────────────────────────┐
 │  app text field        hello wor|       │
 ├─────────────────────────────────────────┤
 │  FUTO action bar  [⌨] [🎤] [✍] [⚙]      │
 │  ╭─────────────────────────────────╮    │  <- ActionWindow (Compose canvas)
 │  │   ~~hello world~~  (ink)        │    │     same slot the voice window uses
 │  ╰─────────────────────────────────╯    │
 └─────────────────────────────────────────┘

 MODE B: "Write in the field"  (Android 14+, Gboard-style)
 ┌─────────────────────────────────────────┐
 │  app text field   ~~hello wor~~  (ink)  │  <- InkWindow overlay owned by the IME
 │                                         │     keyboard stays hidden
 └─────────────────────────────────────────┘
```

Mode A is the proof of concept and reuses FUTO's existing plumbing almost 1:1.
Mode B is the "wow" feature and uses the Android 13-15 stylus IME APIs.

## 2. Architecture

```mermaid
flowchart LR
    subgraph FUTO fork
        A[Action bar<br/>Registry.kt<br/>"handwriting" key] --> W[HandwritingActionWindow<br/>Compose Canvas]
        S[LatinIME.kt<br/>onStartStylusHandwriting<br/>onStylusHandwritingMotionEvent] --> I[Ink overlay<br/>getStylusHandwritingWindow]
        W --> C[InkCapture<br/>MotionEvent -> Stroke/Point x,y,t,pressure]
        I --> C
        C --> R{{HandwritingRecognizer<br/>interface}}
        R --> T[ActionInputTransaction<br/>updatePartial / commit]
        R --> IC[InputConnection<br/>commitText / performHandwritingGesture]
    end
    subgraph Engines, swappable
        R -.-> E1[Open ONNX engine<br/>onnxruntime-android<br/>target]
        R -.-> E2[ML Kit Digital Ink<br/>dev reference only]
        R -.-> E3[Zinnia SVM<br/>CJK per-character]
    end
```

**Key design decision:** the recognizer is an interface from day one. The UI and
IME plumbing never know which engine is behind it. That lets us ship Mode A fast
with whatever engine works, then swap in the open one without touching UI code.

```kotlin
// java/src/org/futo/inputmethod/latin/uix/handwriting/HandwritingRecognizer.kt
data class InkPoint(val x: Float, val y: Float, val t: Long, val pressure: Float)
data class InkStroke(val points: List<InkPoint>)

data class RecognitionContext(
    val preContext: String,        // text left of the cursor, helps disambiguate
    val writingAreaWidthPx: Int,
    val writingAreaHeightPx: Int,
    val locale: String,
)

data class Candidate(val text: String, val score: Float)

interface HandwritingRecognizer : AutoCloseable {
    suspend fun isReady(locale: String): Boolean
    suspend fun recognize(strokes: List<InkStroke>, ctx: RecognitionContext): List<Candidate>
}
```

### 2.1 How Mode A plugs into FUTO (verified against upstream source)

FUTO's voice input is an `Action` with a `windowImpl` that returns an `ActionWindow`.
It commits text through `manager.createInputTransaction()`, calling
`updatePartial()` for live preview and `commit()` for the final result.
Handwriting is the same shape:

| FUTO piece | Voice input does | Handwriting does |
|---|---|---|
| `Action(icon, name, windowImpl, persistentState)` | `VoiceInputAction` | `HandwritingAction` |
| `ActionWindow.WindowContents()` | `RecognizerView` | Compose `Canvas` that records `MotionEvent`s |
| `PersistentActionState` | whisper `ModelManager` | loaded recognizer + model, survives window close |
| `ActionInputTransaction.updatePartial()` | partial transcript | top candidate while pen is down |
| `ActionInputTransaction.commit()` | final transcript | after ~600 ms pen-up idle |
| `Registry.kt AllActionsMap` | `"voice_input" to VoiceInputAction` | `"handwriting" to HandwritingAction` (append at end, indices must stay stable) |

Extra UI bits for the window: candidate strip above the canvas (tap to pick),
backspace / space / newline buttons, and `fixedWindowHeight` so the pad doesn't
jump around.

### 2.2 How Mode B plugs in (Android stylus IME APIs)

Verified from the Android API diffs:

| API level | Android | What we get |
|---|---|---|
| 33 | 13 | `onPrepareStylusHandwriting()`, `onStartStylusHandwriting(): Boolean`, `onStylusHandwritingMotionEvent(MotionEvent)`, `onFinishStylusHandwriting()`, `finishStylusHandwriting()`, `getStylusHandwritingWindow()`. IME declares `android:supportsStylusHandwriting="true"` in `method.xml`; `InputMethodInfo.supportsStylusHandwriting()`. |
| 34 | 14 | `EditText` starts handwriting by default when a stylus touches it. `setStylusHandwritingSessionTimeout(Duration)`, `onUpdateEditorToolType(int)`. Handwriting gestures via `InputConnection.performHandwritingGesture` (delete, select, insert, join/split). Delegation for fake text fields. |
| 35 | 15 | `onStartConnectionlessStylusHandwriting(int, CursorAnchorInfo)` and `finishConnectionlessStylusHandwriting(CharSequence)` for apps that don't own an `InputConnection`. |

Flow in `LatinIME.kt`:

```
stylus touches EditText (system decides) ──► onPrepareStylusHandwriting()   // warm the model
                                        ──► onStartStylusHandwriting()      // return true, inflate ink view
                                                                            //   into getStylusHandwritingWindow()
         repeat while writing          ──► onStylusHandwritingMotionEvent() // feed InkCapture, draw ink
         pen idle > timeout            ──► recognizer.recognize()           // commit via InputConnection
                                        ──► finishStylusHandwriting()
```

FUTO currently declares **no** stylus support in `method.xml`, so this is net-new.

**Open risk (unverified):** whether HyperOS 2 keeps the Android 14 stylus
handwriting framework intact. Gboard's "Write in text fields" launched on Pixel and
Galaxy tablets; no source confirms it on Xiaomi. **Test before building Mode B:**
install Gboard 13.7+ on the Pad 7 and look for *Settings → Write in text fields*.
If it appears and works, the framework path is open to us too. If not, Mode A is
still fully functional because it needs none of these APIs.

## 3. Recognition engine: the real problem

This is where the privacy question actually lives. Honest comparison:

| Engine | Open? | Phones home? | Latin cursive quality | Size | Effort | Verdict |
|---|---|---|---|---|---|---|
| **ML Kit Digital Ink** (what Gboard uses) | No, Google binary | Recognition is on-device. Model (~20 MB/lang) is downloaded from Google once. Unverified whether it needs Play Services. | Excellent | ~20 MB | Days | Dev reference and accuracy baseline only. Not the shipping engine. |
| **Offline HTR via ONNX** (render strokes to a line image, CRNN+CTC or TrOCR-small) | Yes | Never | Good for print, OK for cursive. Throws away pen timing. | 10 MB int8 CRNN to ~230 MB float TrOCR | Weeks | **Phase 2 target.** Use `onnxruntime-android`. Start from an existing IAM-trained CRNN. |
| **Own online recognizer** (BLSTM+CTC on Bézier-resampled strokes, Google's 2019 recipe, trained on IAM-OnDB) | Yes | Never | Potentially best, uses timing + order | < 5 MB | Weeks to months, needs GPU time | Phase 3 stretch goal. |
| **Zinnia** (SVM, MIT) | Yes | Never | Per-character only, built for CJK | Small | Days | Only if you want Chinese/Japanese boxes. |
| **MyScript / WritePad** | Commercial / GPLv3 abandoned | n/a | Great / unknown | | | Skip. |

**Recommendation (confidence: medium-high):** build the interface, validate the
whole UX in Mode A against ML Kit behind a debug build flag, measure its accuracy
on your own handwriting, then replace it with the ONNX engine and keep ML Kit only
as a benchmark. Being skeptical: an open Latin cursive model that matches Gboard
does not exist off the shelf today. Expect to fine-tune on your own samples. The
pad UI makes collecting those samples trivial (long-press candidate → "that was
wrong, correct text is…" → saves stroke+label pair locally).

## 4. Phased plan

```
Phase 0  Fork + build          ░░  1 evening
Phase 1  Mode A pad, any engine ████  1-2 weekends
Phase 2  Open ONNX engine      ██████  2-4 weekends, mostly model work
Phase 3  Mode B in-field       ████  1-2 weekends, gated on HyperOS test
Phase 4  Gestures + polish     ███  ongoing
```

### Phase 0: fork and build (confidence: high)
- `git clone --recursive https://gitlab.futo.org/keyboard/latinime.git` (GitHub is a mirror).
- `./gradlew assembleUnstableDebug`, sideload to the Pad 7, confirm it runs unmodified.
- Enable stylus simulation for emulator testing: `adb shell setprop debug.input.simulate_stylus_with_touch true`.
- Run the Gboard "Write in text fields" check from §2.2 and record the result here.

### Phase 1: handwriting pad (confidence: high)
1. `uix/handwriting/HandwritingRecognizer.kt` interface + `InkCapture.kt` (MotionEvent → strokes, use `getHistoricalX/Y/EventTime/Pressure` so we don't drop samples, filter `TOOL_TYPE_STYLUS` optionally).
2. `uix/handwriting/HandwritingAction.kt` + `HandwritingActionWindow` (Compose Canvas, candidate strip, idle-commit timer).
3. Register `"handwriting"` in `Registry.kt`, add to default Favorites.
4. Engine: `MlKitRecognizer` under a `debugEngines` flag to validate UX. Deliverable: write "hello world" into any app.

### Phase 2: open engine (confidence: medium)
1. Add `com.microsoft.onnxruntime:onnxruntime-android`.
2. `StrokeRasterizer` (strokes → normalized 32-64 px tall line bitmap).
3. `OnnxHtrRecognizer` loading an IAM-trained CRNN+CTC model from app storage (user drops the `.onnx` in, same way FUTO handles whisper models: no download code, no network).
4. Greedy decode, then beam search with FUTO's own dictionary as the language model (`DictionaryFacilitator` is right there).
5. Local correction dataset collection from the pad UI; fine-tune offline on a PC.
6. Remove the ML Kit dependency from release builds entirely.

### Phase 3: write in the field (confidence: medium, gated on §2.2 test)
1. `android:supportsStylusHandwriting="true"` in `method.xml`.
2. Override the six API-33 methods in `LatinIME.kt`; ink view in `getStylusHandwritingWindow()`.
3. `setStylusHandwritingSessionTimeout` tuned to your writing speed.
4. Reuse the same recognizer instance from Phase 1's `PersistentActionState`.

### Phase 4: gestures and polish
- Scratch-out to delete, caret to insert space, via `performHandwritingGesture` (API 34).
- Pressure-weighted ink rendering (Focus Pen gives us 8k levels, use them for the ink look only).
- Palm rejection: ignore `TOOL_TYPE_FINGER` while a stylus stroke is active.
- Android 15 connectionless mode for apps without a real `InputConnection`.

## 5. Licensing and upstreaming, read this before publishing anything

- FUTO Source First License 1.1: you may copy, modify and build for personal and
  non-commercial use. You may distribute modified builds **only free of charge, for
  non-commercial purposes, with a prominent "modified" notice**, keeping FUTO's
  notices and payment-related functionality intact.
- Upstream PRs require signing FUTO's CLA, and their README explicitly asks
  contributors **not to submit AI-generated pull requests or issues**. Code drafted
  with Claude in this repo therefore stays a personal fork unless you rewrite and
  own it yourself, or ask FUTO first. Flagging this plainly so it's not a surprise.
- If a fully FOSS home matters more than FUTO specifically, HeliBoard (GPL-3,
  also a LatinIME descendant) is the obvious alternative base. Same Android IME
  APIs, no CLA, but you lose FUTO's action-window system and whisper integration.
- IAM-OnDB and IAM offline datasets are research-use licences. Fine for a personal
  model; not fine to bake into something you sell.

## 6. Open questions to answer on the device

- [ ] Does Gboard's "Write in text fields" appear on the Pad 7 under HyperOS 2?
- [ ] Does the Focus Pen report `TOOL_TYPE_STYLUS` and pressure through standard `MotionEvent`s in a plain Android app? (Quick test app, 20 lines.)
- [ ] Does ML Kit Digital Ink work with Play Services firewalled / absent? (Only matters for the dev-phase baseline.)
- [ ] Which hand do you write with and how fast? Sets the idle-commit timeout default.

## Sources

- FUTO Keyboard repo and README: https://github.com/futo-org/android-keyboard
- FUTO Source First License 1.1: https://github.com/futo-org/android-keyboard/blob/master/LICENSE.md
- Android stylus input in text fields (Android 14): https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/stylus-input-in-text-fields
- Android custom text editors / IME responsibilities: https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/custom-text-editors
- InputMethodService API 33 diff: https://developer.android.com/sdk/api_diff/33/changes/android.inputmethodservice.InputMethodService
- InputMethodService API 34 diff: https://developer.android.com/sdk/api_diff/34/changes/android.inputmethodservice.InputMethodService
- InputMethodService API 35 diff: https://developer.android.com/sdk/api_diff/35/changes/android.inputmethodservice.InputMethodService
- ML Kit Digital Ink Recognition: https://developers.google.com/ml-kit/vision/digital-ink-recognition
- Gboard handwriting rollout: https://www.gsmarena.com/gboard_now_supports_handwriting_input_in_text_fields_on_pixel_and_galaxy_tablets-news-60952.php
- Fast multi-language LSTM online handwriting recognition (Google, 2019): https://link.springer.com/article/10.1007/s10032-020-00350-4
- Qualcomm TrOCR size/latency figures: https://huggingface.co/qualcomm/TrOCR
- DE·HTR 10 MB int8 ONNX line recognizer: https://huggingface.co/naeyn/de-htr-web
- Zinnia: https://packages.fedoraproject.org/pkgs/zinnia/zinnia
