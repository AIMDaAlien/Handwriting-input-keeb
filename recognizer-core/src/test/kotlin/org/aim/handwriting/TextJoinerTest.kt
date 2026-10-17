package org.aim.handwriting

import kotlin.test.Test
import kotlin.test.assertEquals

class TextJoinerTest {
    @Test fun `empty field gets the bare word`() {
        assertEquals("Hello", TextJoiner.join("Hello", "", ""))
        assertEquals("Hello", TextJoiner.join("Hello", null, null))
    }

    @Test fun `word after word gets a leading space`() {
        assertEquals(" world", TextJoiner.join("world", "hello", ""))
    }

    @Test fun `no double space after existing whitespace or newline`() {
        assertEquals("world", TextJoiner.join("world", "hello ", ""))
        assertEquals("world", TextJoiner.join("world", "hello\n", ""))
    }

    @Test fun `punctuation attaches to the previous word`() {
        assertEquals(".", TextJoiner.join(".", "hello", ""))
        assertEquals("?", TextJoiner.join("?", "really", ""))
    }

    @Test fun `no space after an opening bracket or quote`() {
        assertEquals("aside", TextJoiner.join("aside", "(", ""))
        assertEquals("quote", TextJoiner.join("quote", "\"", ""))
    }

    @Test fun `trailing space only when inserting before a word`() {
        assertEquals(" new ", TextJoiner.join("new", "an", "word"))
        assertEquals(" new", TextJoiner.join("new", "an", " word"))
        assertEquals(" new", TextJoiner.join("new", "an", ", then"))
    }

    @Test fun `case and punctuation are kept as written`() {
        assertEquals(" Paris.", TextJoiner.join("  Paris.  ", "to", ""))
    }

    @Test fun `blank recognition commits nothing`() {
        assertEquals("", TextJoiner.join("   ", "hello", "world"))
    }
}
