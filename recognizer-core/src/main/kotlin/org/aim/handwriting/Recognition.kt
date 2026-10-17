package org.aim.handwriting

/**
 * What the recognizer is allowed to know besides the ink itself.
 * Deliberately small: the engine never sees the full document.
 */
data class RecognitionContext(
    /** BCP-47 tag, e.g. "en-US". Engines pick a model from this. */
    val locale: String,
    /** Up to a few words left of the cursor. Helps disambiguate "l"/"1"/"I". May be empty. */
    val preContext: String = "",
    /** Size of the surface the ink was drawn on, in the ink's coordinate units. */
    val writingAreaWidth: Float = 0f,
    val writingAreaHeight: Float = 0f,
    /** Caller's hint that the user is writing a single line (true for keyboards). */
    val singleLine: Boolean = true,
    /** Upper bound on candidates the caller will display. */
    val maxCandidates: Int = 5,
) {
    init {
        require(locale.isNotBlank()) { "locale is required" }
        require(maxCandidates >= 1)
    }
}

/** One hypothesis. [score] is engine-specific but higher must always mean better. */
data class Candidate(val text: String, val score: Float)

data class RecognitionResult(
    val candidates: List<Candidate>,
    /** Which engine produced this, for the UI debug overlay and the correction log. */
    val engine: EngineId,
    val latencyMs: Long,
) {
    val best: Candidate? get() = candidates.firstOrNull()
    val isEmpty: Boolean get() = candidates.isEmpty()

    companion object {
        fun empty(engine: EngineId, latencyMs: Long = 0) = RecognitionResult(emptyList(), engine, latencyMs)
    }
}

/** Stable identifiers. Stored in the correction log, so never rename, only add. */
enum class EngineId {
    /** Test double, deterministic. */
    FAKE,
    /** Google ML Kit Digital Ink. Dev-only baseline, excluded from release builds. */
    MLKIT,
    /** Open image-based line recognizer run through ONNX Runtime. Phase 2. */
    ONNX_HTR,
    /** Own stroke-based BLSTM/CTC model. Phase 3. */
    ONNX_ONLINE,
    /** Zinnia SVM per-character engine. CJK. */
    ZINNIA,
}

sealed class RecognizerException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Model for the requested locale is not installed. UI should show how to add one. */
    class ModelMissing(val locale: String, val engine: EngineId) :
        RecognizerException("No $engine model for locale '$locale'")
    class EngineFailure(engine: EngineId, cause: Throwable) :
        RecognizerException("$engine failed: ${cause.message}", cause)
}
