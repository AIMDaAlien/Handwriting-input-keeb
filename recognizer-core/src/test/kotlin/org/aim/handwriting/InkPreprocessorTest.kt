package org.aim.handwriting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InkPreprocessorTest {
    @Test fun `normalize puts ink at origin with unit height`() {
        val ink = Ink(listOf(TestInk.stroke(10f, 20f, 10f, 220f, 110f, 220f)))
        val n = InkPreprocessor.normalize(ink)
        assertEquals(0f, n.bounds.left, 1e-5f); assertEquals(0f, n.bounds.top, 1e-5f)
        assertEquals(1f, n.bounds.height, 1e-5f)
        assertEquals(0.5f, n.bounds.width, 1e-5f) // aspect preserved
    }

    @Test fun `normalize of a flat dash scales by width`() {
        val n = InkPreprocessor.normalize(Ink(listOf(TestInk.dash())), 10f)
        assertEquals(10f, n.bounds.width, 1e-5f)
        assertTrue(n.strokes[0].points.all { it.y == 0f })
    }

    @Test fun `resample yields equidistant points and keeps ends`() {
        val s = InkPreprocessor.resample(TestInk.dash(), 10f)
        val xs = s.points.map { it.x }
        assertEquals(0f, xs.first()); assertEquals(100f, xs.last())
        assertEquals(11, xs.size)
        for (i in 1 until xs.size) assertEquals(10f, xs[i] - xs[i - 1], 1e-3f)
        // time interpolated monotonically
        assertTrue(s.points.zipWithNext().all { (a, b) -> b.t >= a.t })
    }

    @Test fun `resample across a corner carries remainder`() {
        val s = InkPreprocessor.resample(TestInk.ell().strokes[0], 30f) // total length 160
        val pts = s.points
        // expect 0,30,60,90,120,150 along path + endpoint = 7
        assertEquals(7, pts.size)
        // 120 along path = 20 px into the horizontal leg
        assertEquals(20f, pts[4].x, 1e-3f); assertEquals(100f, pts[4].y, 1e-3f)
    }

    @Test fun `simplify removes collinear points, keeps corners`() {
        val s = TestInk.stroke(0f, 0f, 0f, 25f, 0f, 50f, 0f, 100f, 30f, 100f, 60f, 100f)
        val simple = InkPreprocessor.simplify(s, 0.5f)
        assertEquals(listOf(0f to 0f, 0f to 100f, 60f to 100f), simple.points.map { it.x to it.y })
    }

    @Test fun `dropNoise removes single-point taps`() {
        val ink = Ink(listOf(TestInk.dash(), InkStroke(listOf(InkPoint(5f, 5f, 0)))))
        assertEquals(1, InkPreprocessor.dropNoise(ink, minLength = 3f).strokes.size)
    }
}
