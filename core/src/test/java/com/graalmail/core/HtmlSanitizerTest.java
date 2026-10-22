/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.graalmail.core.text.HtmlSanitizer;
import org.junit.jupiter.api.Test;

class HtmlSanitizerTest {

    @Test
    void removesScripts() {
        String sanitized = HtmlSanitizer.sanitize("<p>hello</p><script>alert('x')</script>");
        assertFalse(sanitized.contains("script"));
        assertTrue(sanitized.contains("hello"));
    }

    @Test
    void removesEventHandlers() {
        String sanitized = HtmlSanitizer.sanitize("<div onclick=\"steal()\">text</div>");
        assertFalse(sanitized.toLowerCase().contains("onclick"));
    }

    @Test
    void blocksRemoteImagesByDefault() {
        String sanitized = HtmlSanitizer.sanitize("<img src=\"http://tracker.example/pixel.gif\">");
        assertFalse(sanitized.contains("tracker.example/pixel.gif\" src=\"http"));
        assertTrue(sanitized.contains("about:blank"));
    }

    @Test
    void keepsInlineImages() {
        String sanitized = HtmlSanitizer.sanitize("<img src=\"cid:image1\">");
        assertTrue(sanitized.contains("cid:image1"));
    }

    @Test
    void keepsLinksButDropsDangerousSchemes() {
        assertTrue(HtmlSanitizer.sanitize("<a href=\"https://example.com\">x</a>").contains("example.com"));
        assertFalse(HtmlSanitizer.sanitize("<a href=\"javascript:alert(1)\">x</a>")
                .toLowerCase().contains("javascript:"));
    }

    @Test
    void convertsHtmlToText() {
        assertTrue(HtmlSanitizer.toPlainText("<p>Hello</p><p>World</p>").contains("Hello"));
    }
}
