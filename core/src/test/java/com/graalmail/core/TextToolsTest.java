/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.graalmail.core.text.TextTools;
import org.junit.jupiter.api.Test;

class TextToolsTest {

    @Test
    void snippetIsTruncated() {
        assertEquals("abc…", TextTools.snippet("abcdef", 4));
        assertEquals("ab", TextTools.snippet("ab", 4));
    }

    @Test
    void safeFileNameRemovesPathSeparators() {
        assertEquals(".._.._etc_passwd", TextTools.safeFileName("../../etc/passwd", "file"));
        assertEquals("file", TextTools.safeFileName("   ", "file"));
    }

    @Test
    void initialsUsesFirstTwoWords() {
        assertEquals("JD", TextTools.initials("Jane Doe"));
        assertEquals("?", TextTools.initials(""));
    }
}
