package org.aim.handwriting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InkTest {
    @Test fun `stroke needs a point`() { assertFailsWith<IllegalArgumentException> { InkStroke(emptyList()) } }

    @Test fun `bounds and length`() {
        val ink = TestInk.ell()
        assertEquals(Bounds(0f, 0f, 60f, 100f), ink.bounds)
        assertEquals(160f, ink.strokes[0].length, 1e-4f)
    }

    @Test fun `duration spans strokes`() { assertEquals(508L, TestInk.tee().duration) }

    @Test fun `builder drops jitter but keeps endpoints`() {
        val b = StrokeBuilder(minDistancePx = 2f)
        b.down(InkPoint(0f, 0f, 0))
        b.move(InkPoint(0.5f, 0f, 8))   // dropped
        b.move(InkPoint(3f, 0f, 16))    // kept
        b.move(InkPoint(3.1f, 0f, 24))  // dropped
        b.up(InkPoint(3.2f, 0f, 32))    // last point always kept
        val ink = b.take()
        assertEquals(1, ink.strokes.size)
        assertEquals(listOf(0f, 3f, 3.2f), ink.strokes[0].points.map { it.x })
        assertFalse(b.isPenDown)
    }

    @Test fun `missed up closes previous stroke`() {
        val b = StrokeBuilder()
        b.down(InkPoint(0f, 0f, 0)); b.move(InkPoint(5f, 0f, 8))
        b.down(InkPoint(0f, 20f, 100))
        assertEquals(1, b.strokeCount)
        assertTrue(b.isPenDown)
        assertEquals(2, b.snapshot().strokes.size)
    }

    @Test fun `undo removes last finished stroke only`() {
        val b = StrokeBuilder()
        b.down(InkPoint(0f, 0f, 0)); b.up(InkPoint(5f, 0f, 8))
        b.down(InkPoint(0f, 20f, 100)); b.up(InkPoint(5f, 20f, 108))
        assertTrue(b.undoLastStroke())
        assertEquals(1, b.take().strokes.size)
        assertFalse(b.undoLastStroke())
    }
}
