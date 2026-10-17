package org.aim.handwriting

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Row-major grayscale image, 0 = background, 1 = full ink. */
class RasterImage(val width: Int, val height: Int, val pixels: FloatArray = FloatArray(width * height)) {
    init { require(pixels.size == width * height) }
    operator fun get(x: Int, y: Int): Float = pixels[y * width + x]
    fun inkCoverage(): Float = pixels.count { it > 0.5f }.toFloat() / pixels.size

    /** Handy for eyeballing in a test log. */
    fun toAscii(): String = buildString {
        for (y in 0 until height) {
            for (x in 0 until width) append(if (this@RasterImage[x, y] > 0.5f) '#' else if (this@RasterImage[x, y] > 0.1f) '+' else '.')
            append('\n')
        }
    }
}

/**
 * Turns ink into the fixed-height line image an offline HTR model (CRNN, TrOCR)
 * expects. Height is fixed, width follows the ink's aspect ratio up to [maxWidth].
 * The pen is drawn with a round brush of [penRadius] pixels, anti-aliased by
 * supersampling the brush footprint. Pure Kotlin: no android.graphics.
 */
class StrokeRasterizer(
    val height: Int = 32,
    val maxWidth: Int = 1024,
    val padding: Int = 2,
    val penRadius: Float = 1.2f,
) {
    init { require(height > 2 * padding); require(penRadius > 0f) }

    fun rasterize(ink: Ink): RasterImage {
        if (ink.isEmpty) return RasterImage(height, height)
        val inner = (height - 2 * padding).toFloat()
        val norm = InkPreprocessor.normalize(ink, inner)
        val b = norm.bounds
        val width = min(maxWidth, max(height, ceil(b.width).toInt() + 2 * padding))
        // If the width was clamped, squash horizontally to fit.
        val xScale = if (b.width > 0f) min(1f, (width - 2 * padding) / b.width) else 1f
        val img = RasterImage(width, height)
        for (s in norm.strokes) {
            val pts = s.points
            if (pts.size == 1) {
                dot(img, pts[0].x * xScale + padding, pts[0].y + padding)
            } else {
                for (i in 1 until pts.size) {
                    line(img,
                        pts[i - 1].x * xScale + padding, pts[i - 1].y + padding,
                        pts[i].x * xScale + padding, pts[i].y + padding)
                }
            }
        }
        return img
    }

    private fun line(img: RasterImage, x0: Float, y0: Float, x1: Float, y1: Float) {
        val dx = x1 - x0; val dy = y1 - y0
        val steps = max(1, ceil(max(abs(dx), abs(dy)) / 0.5f).toInt())
        for (i in 0..steps) {
            val f = i.toFloat() / steps
            dot(img, x0 + dx * f, y0 + dy * f)
        }
    }

    /** Stamp a round brush centred at (cx, cy) with coverage-based anti-aliasing. */
    private fun dot(img: RasterImage, cx: Float, cy: Float) {
        val r = penRadius
        val xs = max(0, floor(cx - r).toInt()); val xe = min(img.width - 1, ceil(cx + r).toInt())
        val ys = max(0, floor(cy - r).toInt()); val ye = min(img.height - 1, ceil(cy + r).toInt())
        for (y in ys..ye) for (x in xs..xe) {
            val cov = coverage(x, y, cx, cy, r)
            if (cov > 0f) {
                val idx = y * img.width + x
                img.pixels[idx] = min(1f, img.pixels[idx] + cov)
            }
        }
    }

    /** Fraction of pixel (x,y) inside the circle, by 4x4 supersampling. */
    private fun coverage(x: Int, y: Int, cx: Float, cy: Float, r: Float): Float {
        var inside = 0
        val r2 = r * r
        for (sy in 0 until 4) for (sx in 0 until 4) {
            val px = x + (sx + 0.5f) / 4f; val py = y + (sy + 0.5f) / 4f
            val ddx = px - cx; val ddy = py - cy
            if (ddx * ddx + ddy * ddy <= r2) inside++
        }
        return inside / 16f
    }
}
