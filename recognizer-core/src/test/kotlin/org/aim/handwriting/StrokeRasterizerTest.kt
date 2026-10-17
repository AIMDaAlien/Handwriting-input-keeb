package org.aim.handwriting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StrokeRasterizerTest {
    @Test fun `empty ink gives blank square`() {
        val img = StrokeRasterizer(height = 16).rasterize(Ink.EMPTY)
        assertEquals(16, img.width); assertEquals(0f, img.inkCoverage())
    }

    @Test fun `tall ink fills height and width follows aspect`() {
        val r = StrokeRasterizer(height = 32, padding = 2, penRadius = 1f)
        val img = r.rasterize(TestInk.ell()) // 60 wide x 100 tall -> inner 28 tall, ~17 wide
        assertEquals(32, img.height)
        assertEquals(32, img.width) // min width is height
        // vertical leg at x≈2 must be inked top to bottom
        for (y in 2..29) assertTrue(img[2, y] > 0.3f, "row $y not inked: \n${img.toAscii()}")
        // horizontal leg along bottom
        for (x in 2..18) assertTrue(img[x, 29] > 0.3f, "col $x not inked: \n${img.toAscii()}")
        // top-right quadrant stays empty
        assertEquals(0f, img[20, 5])
    }

    @Test fun `wide ink is clamped to maxWidth`() {
        val wide = Ink(listOf(TestInk.stroke(0f, 0f, 5000f, 0f, 5000f, 10f)))
        val img = StrokeRasterizer(height = 16, maxWidth = 64).rasterize(wide)
        assertEquals(64, img.width)
        assertTrue(img.inkCoverage() > 0.02f)
    }

    @Test fun `single tap draws a dot`() {
        val img = StrokeRasterizer(height = 16).rasterize(Ink(listOf(InkStroke(listOf(InkPoint(3f, 3f, 0))))))
        assertTrue(img.pixels.any { it > 0.5f })
    }
}
