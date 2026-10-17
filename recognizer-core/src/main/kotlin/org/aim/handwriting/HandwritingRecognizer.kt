package org.aim.handwriting

/**
 * The one seam between UI/IME plumbing and whatever does the actual recognizing.
 *
 * Contract:
 *  - Implementations must be safe to call from any coroutine; they decide their own dispatcher.
 *  - [recognize] must never touch the network. If a model is absent it throws
 *    [RecognizerException.ModelMissing]; it does not fetch one.
 *  - [recognize] is cancellable. Callers cancel the previous call when new ink arrives.
 *  - Returning an empty result is valid (nothing legible). Throwing is for real failures.
 *  - [close] releases native handles. The instance is dead afterwards.
 */
interface HandwritingRecognizer : AutoCloseable {
    val id: EngineId

    /** True when [recognize] for [locale] will not throw [RecognizerException.ModelMissing]. */
    suspend fun isReady(locale: String): Boolean

    /** Optional eager load so the first stroke is not the one that pays for model init. */
    suspend fun warmUp(locale: String) {}

    suspend fun recognize(ink: Ink, context: RecognitionContext): RecognitionResult

    override fun close() {}
}

/**
 * Picks an engine per locale and falls through on [RecognizerException.ModelMissing].
 * Order matters: put the engine you trust most first.
 */
class CompositeRecognizer(private val engines: List<HandwritingRecognizer>) : HandwritingRecognizer {
    init { require(engines.isNotEmpty()) }

    override val id: EngineId get() = engines.first().id

    override suspend fun isReady(locale: String): Boolean = engines.any { it.isReady(locale) }

    override suspend fun warmUp(locale: String) {
        engines.firstOrNull { it.isReady(locale) }?.warmUp(locale)
    }

    override suspend fun recognize(ink: Ink, context: RecognitionContext): RecognitionResult {
        var lastMissing: RecognizerException.ModelMissing? = null
        for (e in engines) {
            try {
                return e.recognize(ink, context)
            } catch (m: RecognizerException.ModelMissing) {
                lastMissing = m
            }
        }
        throw lastMissing ?: RecognizerException.ModelMissing(context.locale, id)
    }

    override fun close() = engines.forEach { it.close() }
}
