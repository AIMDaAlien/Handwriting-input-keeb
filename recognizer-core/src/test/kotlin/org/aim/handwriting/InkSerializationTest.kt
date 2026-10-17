package org.aim.handwriting

import kotlin.test.Test
import kotlin.test.assertEquals

class InkSerializationTest {
    @Test fun `round trip preserves geometry, relative time and pressure`() {
        val ink = TestInk.tee()
        val ctx = RecognitionContext("en-US", writingAreaWidth = 800f, writingAreaHeight = 300f)
        val dto = InkSerialization.toDto(ink, ctx, label = "T", engine = EngineId.FAKE, engineGuess = "7", nowEpochMs = 1_700_000_000_000)
        val text = InkSerialization.encode(dto)
        val back = InkSerialization.toInk(InkSerialization.decode(text))
        assertEquals(ink.strokes.size, back.strokes.size)
        assertEquals(ink.strokes.map { s -> s.points.map { it.x to it.y } }, back.strokes.map { s -> s.points.map { it.x to it.y } })
        assertEquals(ink.strokes.map { s -> s.points.map { it.pressure } }, back.strokes.map { s -> s.points.map { it.pressure } })
        // times are relative to first sample
        assertEquals(0L, back.strokes[0].points[0].t)
        assertEquals(500L, back.strokes[1].points[0].t)
        assertEquals("T", InkSerialization.decode(text).label)
    }
}
