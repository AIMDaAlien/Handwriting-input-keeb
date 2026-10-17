package org.aim.handwriting.ime

import android.view.MotionEvent
import org.aim.handwriting.InkPoint
import org.aim.handwriting.StrokeBuilder

/**
 * Thin MotionEvent -> [StrokeBuilder] bridge. Tracks one pointer at a time.
 *
 * Palm rejection, in order of precedence:
 *  - while a stylus stroke is live, every other pointer is ignored;
 *  - a stylus arriving during a finger stroke drops the finger stroke (it was a palm);
 *  - once a stylus has been seen in this session, fingers never start strokes.
 * Before any stylus is seen, fingers draw, so the pad is usable without the pen.
 */
class StylusInkAdapter(
    private val builder: StrokeBuilder,
    private val listener: Listener,
) {
    interface Listener {
        fun onStrokeStart()
        fun onStrokeMove()
        fun onStrokeEnd()
        fun onStrokeCancelled()
    }

    private var activePointerId = NO_POINTER
    private var activeIsStylus = false

    var stylusSeen = false
        private set

    val isStrokeActive: Boolean get() = activePointerId != NO_POINTER

    /** Pointer index of the live stroke in [ev], or -1. */
    fun activeIndexIn(ev: MotionEvent): Int =
        if (activePointerId == NO_POINTER) -1 else ev.findPointerIndex(activePointerId)

    fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> onDown(ev, ev.actionIndex)
            MotionEvent.ACTION_MOVE -> onMove(ev)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> onUp(ev, ev.actionIndex)
            MotionEvent.ACTION_CANCEL -> cancelActive()
        }
        return true
    }

    private fun onDown(ev: MotionEvent, index: Int) {
        val isStylus = ev.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS
        if (activePointerId != NO_POINTER) {
            if (activeIsStylus || !isStylus) return
            cancelActive()
        }
        if (!isStylus && stylusSeen) return
        if (isStylus) stylusSeen = true

        activePointerId = ev.getPointerId(index)
        activeIsStylus = isStylus
        builder.down(pointAt(ev, index))
        listener.onStrokeStart()
    }

    private fun onMove(ev: MotionEvent) {
        val index = ev.findPointerIndex(activePointerId)
        if (index < 0) return
        // Batched samples since the last frame; skipping them makes fast strokes jagged.
        for (h in 0 until ev.historySize) {
            builder.move(
                InkPoint(
                    ev.getHistoricalX(index, h),
                    ev.getHistoricalY(index, h),
                    ev.getHistoricalEventTime(h),
                    ev.getHistoricalPressure(index, h),
                )
            )
        }
        builder.move(pointAt(ev, index))
        listener.onStrokeMove()
    }

    private fun onUp(ev: MotionEvent, index: Int) {
        if (ev.getPointerId(index) != activePointerId) return
        // API 33+: the system flags a pointer it decided was a palm.
        if (ev.flags and MotionEvent.FLAG_CANCELED != 0) {
            cancelActive()
            return
        }
        builder.up(pointAt(ev, index))
        activePointerId = NO_POINTER
        listener.onStrokeEnd()
    }

    private fun cancelActive() {
        if (activePointerId == NO_POINTER) return
        builder.cancelCurrent()
        activePointerId = NO_POINTER
        listener.onStrokeCancelled()
    }

    private fun pointAt(ev: MotionEvent, index: Int) =
        InkPoint(ev.getX(index), ev.getY(index), ev.eventTime, ev.getPressure(index))

    private companion object {
        const val NO_POINTER = -1
    }
}
