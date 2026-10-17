package org.aim.handwriting

/** Shared fixtures. */
object TestInk {
    fun stroke(vararg xy: Float, t0: Long = 0, dt: Long = 8, pressure: Float = 0.7f): InkStroke {
        require(xy.size % 2 == 0)
        return InkStroke((xy.indices step 2).map { i -> InkPoint(xy[i], xy[i + 1], t0 + (i / 2) * dt, pressure) })
    }

    /** A horizontal dash from (0,10) to (100,10). */
    fun dash() = stroke(0f, 10f, 50f, 10f, 100f, 10f)

    /** A rough "L": down then right. */
    fun ell() = Ink(listOf(stroke(0f, 0f, 0f, 50f, 0f, 100f, 60f, 100f)))

    /** Two-stroke "T". */
    fun tee() = Ink(listOf(stroke(0f, 0f, 80f, 0f), stroke(40f, 0f, 40f, 100f, t0 = 500)))
}
