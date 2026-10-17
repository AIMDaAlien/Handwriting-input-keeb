package org.aim.handwriting

import kotlinx.coroutines.delay

/**
 * Deterministic engine for tests and for building the UI before any model exists.
 * Default behaviour: one candidate per stroke count ("3 strokes"), plus a
 * scripted answer table so UI tests can assert on real words.
 */
class FakeRecognizer(
    private val readyLocales: Set<String> = setOf("en-US", "en"),
    private val scripted: Map<Int, List<Candidate>> = emptyMap(),
    private val simulatedLatencyMs: Long = 0,
) : HandwritingRecognizer {
    override val id = EngineId.FAKE

    var recognizeCalls = 0
        private set
    var closed = false
        private set

    override suspend fun isReady(locale: String) = locale in readyLocales

    override suspend fun recognize(ink: Ink, context: RecognitionContext): RecognitionResult {
        recognizeCalls++
        if (!isReady(context.locale)) throw RecognizerException.ModelMissing(context.locale, id)
        if (simulatedLatencyMs > 0) delay(simulatedLatencyMs)
        if (ink.isEmpty) return RecognitionResult.empty(id, simulatedLatencyMs)
        val n = ink.strokes.size
        val cands = scripted[n] ?: listOf(Candidate("$n strokes", 1f))
        return RecognitionResult(cands.take(context.maxCandidates), id, simulatedLatencyMs)
    }

    override fun close() { closed = true }
}
