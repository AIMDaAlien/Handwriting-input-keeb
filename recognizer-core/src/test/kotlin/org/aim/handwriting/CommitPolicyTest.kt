package org.aim.handwriting

import org.aim.handwriting.CommitPolicy.Decision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommitPolicyTest {
    @Test fun `nothing happens before any ink`() {
        val p = CommitPolicy(650, 120)
        assertEquals(Decision.NONE, p.onTick(1000))
        assertNull(p.nextDeadline(1000))
    }

    @Test fun `pen down cancels, pen up schedules partial then commit`() {
        val p = CommitPolicy(650, 120)
        assertEquals(Decision.CANCEL_PENDING, p.onPenDown(0))
        assertEquals(Decision.NONE, p.onTick(50))          // pen still down
        assertEquals(Decision.NONE, p.onPenUp(100))
        assertEquals(Decision.NONE, p.onTick(150))         // idle 50 < 120
        assertEquals(120L, p.nextDeadline(100))
        assertEquals(Decision.RECOGNIZE_PARTIAL, p.onTick(230))
        assertEquals(Decision.NONE, p.onTick(300))         // partial already issued
        assertEquals(450L, p.nextDeadline(300))
        assertEquals(Decision.COMMIT, p.onTick(750))
        assertEquals(Decision.NONE, p.onTick(5000))        // reset after commit
    }

    @Test fun `new stroke before commit postpones commit`() {
        val p = CommitPolicy(650, 120)
        p.onPenDown(0); p.onPenUp(100)
        assertEquals(Decision.RECOGNIZE_PARTIAL, p.onTick(230))
        assertEquals(Decision.CANCEL_PENDING, p.onPenDown(600))
        p.onPenUp(700)
        assertEquals(Decision.NONE, p.onTick(750))         // would have committed at 750 without the new stroke
        assertEquals(Decision.RECOGNIZE_PARTIAL, p.onTick(830))
        assertEquals(Decision.COMMIT, p.onTick(1350))
    }

    @Test fun `reset forgets everything`() {
        val p = CommitPolicy(650, 120)
        p.onPenDown(0); p.onPenUp(100); p.reset()
        assertEquals(Decision.NONE, p.onTick(5000))
    }
}
