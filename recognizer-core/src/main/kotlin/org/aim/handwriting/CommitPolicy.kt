package org.aim.handwriting

/**
 * Decides *when* to recognize and when to commit, without knowing *how*.
 * Pure state machine driven by [onPenDown], [onPenUp] and [onTick]; the caller
 * supplies the clock, so this is fully testable without timers.
 *
 * Typical keyboard flow:
 *   pen down ........ cancel any in-flight recognition
 *   pen up .......... schedule a partial recognition (live preview)
 *   idle > commitMs . COMMIT the best candidate and clear the ink
 *
 * [commitIdleMs] is the one number users will want to tune. Slow writers need
 * more; fast writers get annoyed by less.
 */
class CommitPolicy(
    val commitIdleMs: Long = 650,
    val partialDelayMs: Long = 120,
) {
    init { require(partialDelayMs in 0..commitIdleMs) }

    enum class Decision { NONE, CANCEL_PENDING, RECOGNIZE_PARTIAL, COMMIT }

    private var penDown = false
    private var lastPenUpAt: Long = -1
    private var partialIssued = false
    private var hasInk = false

    fun onPenDown(now: Long): Decision {
        penDown = true
        hasInk = true
        partialIssued = false
        return Decision.CANCEL_PENDING
    }

    fun onPenUp(now: Long): Decision {
        penDown = false
        lastPenUpAt = now
        partialIssued = false
        return Decision.NONE
    }

    /** Call on a timer or on each frame. Returns at most one actionable decision. */
    fun onTick(now: Long): Decision {
        if (penDown || !hasInk || lastPenUpAt < 0) return Decision.NONE
        val idle = now - lastPenUpAt
        if (idle >= commitIdleMs) {
            reset()
            return Decision.COMMIT
        }
        if (!partialIssued && idle >= partialDelayMs) {
            partialIssued = true
            return Decision.RECOGNIZE_PARTIAL
        }
        return Decision.NONE
    }

    /** Milliseconds until the next decision could fire, or null if nothing is pending. */
    fun nextDeadline(now: Long): Long? {
        if (penDown || !hasInk || lastPenUpAt < 0) return null
        val idle = now - lastPenUpAt
        val toCommit = commitIdleMs - idle
        val toPartial = if (partialIssued) Long.MAX_VALUE else partialDelayMs - idle
        return maxOf(0L, minOf(toCommit, toPartial))
    }

    /** User tapped a candidate or hit backspace-all: forget pending state. */
    fun reset() {
        penDown = false
        lastPenUpAt = -1
        partialIssued = false
        hasInk = false
    }
}
