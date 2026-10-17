package org.aim.handwriting

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecognizerTest {
    private val ctx = RecognitionContext(locale = "en-US", writingAreaWidth = 800f, writingAreaHeight = 300f)

    @Test fun `fake recognizer counts strokes and honours maxCandidates`() = runTest {
        val r = FakeRecognizer(scripted = mapOf(2 to listOf(Candidate("T", .9f), Candidate("+", .5f), Candidate("t", .1f))))
        val res = r.recognize(TestInk.tee(), ctx.copy(maxCandidates = 2))
        assertEquals(listOf("T", "+"), res.candidates.map { it.text })
        assertEquals("1 strokes", r.recognize(TestInk.ell(), ctx).best?.text)
        assertTrue(r.recognize(Ink.EMPTY, ctx).isEmpty)
    }

    @Test fun `missing model throws, never fetches`() = runTest {
        val r = FakeRecognizer(readyLocales = setOf("en"))
        val e = assertFailsWith<RecognizerException.ModelMissing> { r.recognize(TestInk.ell(), ctx.copy(locale = "de-DE")) }
        assertEquals("de-DE", e.locale)
    }

    @Test fun `composite falls through on missing model and closes all`() = runTest {
        val de = FakeRecognizer(readyLocales = setOf("de-DE"), scripted = mapOf(1 to listOf(Candidate("de", 1f))))
        val en = FakeRecognizer(readyLocales = setOf("en-US"), scripted = mapOf(1 to listOf(Candidate("en", 1f))))
        val c = CompositeRecognizer(listOf(de, en))
        assertEquals("en", c.recognize(TestInk.ell(), ctx).best?.text)
        assertEquals("de", c.recognize(TestInk.ell(), ctx.copy(locale = "de-DE")).best?.text)
        assertTrue(c.isReady("de-DE")); assertTrue(!c.isReady("fr-FR"))
        assertFailsWith<RecognizerException.ModelMissing> { c.recognize(TestInk.ell(), ctx.copy(locale = "fr-FR")) }
        c.close()
        assertTrue(de.closed && en.closed)
    }

    @Test fun `context validates`() {
        assertFailsWith<IllegalArgumentException> { RecognitionContext(locale = " ") }
        assertFailsWith<IllegalArgumentException> { RecognitionContext(locale = "en", maxCandidates = 0) }
    }
}
