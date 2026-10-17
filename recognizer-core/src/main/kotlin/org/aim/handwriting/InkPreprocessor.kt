package org.aim.handwriting

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pure geometry on ink. Every function returns new objects; nothing is mutated.
 * These feed both the rasterizer (Phase 2) and the stroke-sequence model (Phase 3).
 */
object InkPreprocessor {

    /**
     * Translate so the ink's top-left is at (0,0) and scale so its height is
     * [targetHeight]. Aspect ratio is preserved. A single horizontal line (height 0)
     * is scaled by width instead, so a dash does not blow up to infinity.
     */
    fun normalize(ink: Ink, targetHeight: Float = 1f): Ink {
        if (ink.isEmpty) return ink
        val b = ink.bounds
        val scale = when {
            b.height > 1e-6f -> targetHeight / b.height
            b.width > 1e-6f -> targetHeight / b.width
            else -> 1f
        }
        return ink.map { p -> p.copy(x = (p.x - b.left) * scale, y = (p.y - b.top) * scale) }
    }

    /**
     * Resample each stroke so consecutive points are [spacing] apart along the path.
     * Time and pressure are linearly interpolated. First and last points are kept.
     * This is what turns a jittery 240 Hz pen stream into a shape-only sequence.
     */
    fun resample(ink: Ink, spacing: Float): Ink {
        require(spacing > 0f)
        return Ink(ink.strokes.map { resample(it, spacing) })
    }

    fun resample(stroke: InkStroke, spacing: Float): InkStroke {
        val pts = stroke.points
        if (pts.size < 2) return stroke
        val out = ArrayList<InkPoint>()
        out.add(pts.first())
        var carry = 0f // distance already walked since last emitted point
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val seg = a.distanceTo(b)
            if (seg <= 0f) continue
            var d = spacing - carry
            while (d <= seg) {
                val f = d / seg
                out.add(lerp(a, b, f))
                d += spacing
            }
            carry = seg - (d - spacing)
        }
        if (out.last() != pts.last()) out.add(pts.last())
        return InkStroke(out)
    }

    /**
     * Ramer–Douglas–Peucker simplification. Removes points whose perpendicular
     * distance from the chord is under [epsilon]. Good for compact storage and
     * for feeding sequence models fewer, more meaningful points.
     */
    fun simplify(ink: Ink, epsilon: Float): Ink = Ink(ink.strokes.map { simplify(it, epsilon) })

    fun simplify(stroke: InkStroke, epsilon: Float): InkStroke {
        val pts = stroke.points
        if (pts.size < 3) return stroke
        val keep = BooleanArray(pts.size)
        keep[0] = true; keep[pts.size - 1] = true
        rdp(pts, 0, pts.size - 1, epsilon, keep)
        return InkStroke(pts.filterIndexed { i, _ -> keep[i] })
    }

    private fun rdp(pts: List<InkPoint>, s: Int, e: Int, eps: Float, keep: BooleanArray) {
        if (e <= s + 1) return
        var maxD = -1f; var idx = -1
        for (i in s + 1 until e) {
            val d = perpendicularDistance(pts[i], pts[s], pts[e])
            if (d > maxD) { maxD = d; idx = i }
        }
        if (maxD > eps) {
            keep[idx] = true
            rdp(pts, s, idx, eps, keep)
            rdp(pts, idx, e, eps, keep)
        }
    }

    private fun perpendicularDistance(p: InkPoint, a: InkPoint, b: InkPoint): Float {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-6f) return p.distanceTo(a)
        return abs(dy * p.x - dx * p.y + b.x * a.y - b.y * a.x) / len
    }

    /** Drop strokes shorter than [minLength] with fewer than [minPoints] points (pen taps, noise). */
    fun dropNoise(ink: Ink, minLength: Float, minPoints: Int = 2): Ink =
        Ink(ink.strokes.filter { it.points.size >= minPoints || it.length >= minLength })

    private fun lerp(a: InkPoint, b: InkPoint, f: Float) = InkPoint(
        x = a.x + (b.x - a.x) * f,
        y = a.y + (b.y - a.y) * f,
        t = a.t + ((b.t - a.t) * f).toLong(),
        pressure = a.pressure + (b.pressure - a.pressure) * f,
    )
}

inline fun Ink.map(f: (InkPoint) -> InkPoint): Ink = Ink(strokes.map { s -> InkStroke(s.points.map(f)) })
