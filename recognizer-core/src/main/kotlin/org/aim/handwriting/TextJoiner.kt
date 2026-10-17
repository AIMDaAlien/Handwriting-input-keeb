package org.aim.handwriting

/**
 * Decides the spaces around a recognized word before it is committed, from the
 * text on either side of the cursor. Only whitespace is touched: handwriting is
 * taken as written, so no case changes and no punctuation edits (unlike FUTO's
 * Whisper sanitizer, which lowercases and drops trailing punctuation).
 */
object TextJoiner {
    private const val OPENERS = "([{<\"'“‘—/"
    private const val CLOSERS = ")]}>\"'”’—/.,!?:;…%"

    fun join(text: String, before: CharSequence?, after: CharSequence?): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        val b = before?.lastOrNull()
        val a = after?.firstOrNull()

        val lead = b != null && !b.isWhitespace() && b !in OPENERS && t.first() !in CLOSERS
        val trail = a != null && !a.isWhitespace() && a !in CLOSERS
        return (if (lead) " " else "") + t + (if (trail) " " else "")
    }
}
