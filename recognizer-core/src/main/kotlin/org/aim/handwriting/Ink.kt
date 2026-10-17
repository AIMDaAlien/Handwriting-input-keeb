package org.aim.handwriting

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One pen sample. Coordinates are in the capture surface's pixel space.
 * [t] is milliseconds, monotonic within an [Ink] (MotionEvent.eventTime on Android).
 * [pressure] is 0..1 (MotionEvent.getPressure). 1.0 when the device does not report it.
 */
data class InkPoint(val x: Float, val y: Float, val t: Long, val pressure: Float = 1f) {
    fun distanceTo(o: InkPoint): Float {
        val dx = x - o.x
        val dy = y - o.y
        return sqrt(dx * dx + dy * dy)
    }
}

/** Everything between one pen-down and the next pen-up. */
data class InkStroke(val points: List<InkPoint>) {
    init {
        require(points.isNotEmpty()) { "A stroke needs at least one point" }
    }

    val startTime: Long get() = points.first().t
    val endTime: Long get() = points.last().t
    val duration: Long get() = endTime - startTime

    val bounds: Bounds by lazy { Bounds.of(points) }

    /** Sum of segment lengths. */
    val length: Float by lazy {
        var acc = 0f
        for (i in 1 until points.size) acc += points[i].distanceTo(points[i - 1])
        acc
    }
}

/** An ordered list of strokes: what the user has written since the last commit. */
data class Ink(val strokes: List<InkStroke>) {
    val isEmpty: Boolean get() = strokes.isEmpty()
    val pointCount: Int get() = strokes.sumOf { it.points.size }

    val bounds: Bounds by lazy {
        if (strokes.isEmpty()) Bounds.EMPTY else strokes.map { it.bounds }.reduce(Bounds::union)
    }

    /** Wall-clock span from first pen-down to last pen-up, in ms. */
    val duration: Long get() = if (strokes.isEmpty()) 0 else strokes.last().endTime - strokes.first().startTime

    fun plus(stroke: InkStroke): Ink = Ink(strokes + stroke)

    companion object {
        val EMPTY = Ink(emptyList())
    }
}

data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isEmpty: Boolean get() = width <= 0f && height <= 0f && this === EMPTY
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun union(o: Bounds): Bounds {
        if (this === EMPTY) return o
        if (o === EMPTY) return this
        return Bounds(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))
    }

    companion object {
        val EMPTY = Bounds(0f, 0f, 0f, 0f)

        fun of(points: List<InkPoint>): Bounds {
            var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY
            var r = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
            for (p in points) {
                if (p.x < l) l = p.x; if (p.x > r) r = p.x
                if (p.y < t) t = p.y; if (p.y > b) b = p.y
            }
            return Bounds(l, t, r, b)
        }
    }
}

/**
 * Accumulates raw samples into strokes. Platform code (the Android MotionEvent
 * adapter) calls [down], [move] and [up]; nothing here knows about Android.
 *
 * Samples closer than [minDistancePx] to the previous sample are dropped so that
 * a resting pen does not produce thousands of identical points. The final sample
 * of a stroke is always kept.
 */
class StrokeBuilder(private val minDistancePx: Float = 0.5f) {
    private var current: MutableList<InkPoint>? = null
    private val finished = mutableListOf<InkStroke>()

    val isPenDown: Boolean get() = current != null
    val strokeCount: Int get() = finished.size

    fun down(p: InkPoint) {
        // A down while already down means we missed an up. Close the old stroke.
        if (current != null) upWithoutPoint()
        current = mutableListOf(p)
    }

    fun move(p: InkPoint) {
        val c = current ?: return
        if (c.last().distanceTo(p) >= minDistancePx) c.add(p)
    }

    fun up(p: InkPoint) {
        val c = current ?: return
        if (c.last() != p) c.add(p)
        finished.add(InkStroke(c.toList()))
        current = null
    }

    private fun upWithoutPoint() {
        val c = current ?: return
        finished.add(InkStroke(c.toList()))
        current = null
    }

    /** Drop the in-progress stroke (palm rejection, cancel). */
    fun cancelCurrent() { current = null }

    /** Snapshot of finished strokes plus the one in progress, if any. */
    fun snapshot(): Ink {
        val c = current
        return if (c == null || c.isEmpty()) Ink(finished.toList()) else Ink(finished + InkStroke(c.toList()))
    }

    /** Returns completed strokes and clears everything. */
    fun take(): Ink {
        val ink = Ink(finished.toList())
        finished.clear()
        current = null
        return ink
    }

    fun undoLastStroke(): Boolean = finished.removeLastOrNull() != null

    fun clear() { finished.clear(); current = null }
}
