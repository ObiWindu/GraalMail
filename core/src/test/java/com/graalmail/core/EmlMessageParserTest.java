/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.graalmail.core.domain.Message;
import com.graalmail.core.mail.EmlMessageParser;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EmlMessageParserTest {

    @Test
    void parsesSinglePartMessage() {
        String eml = "From: Alice <alice@example.com>\r\n"
                + "Subject: Hello\r\n"
                + "Date: Mon, 01 Jan 2024 10:00:00 +0000\r\n"
                + "Message-ID: <abc@example.com>\r\n"
                + "\r\n"
                + "Hi there.\r\n";
        Message message = EmlMessageParser.parse(eml.getBytes(StandardCharsets.UTF_8), "a1", "inbox", "m1");
        assertEquals("Hello", message.subject());
        assertEquals("alice@example.com", message.from().email());
        assertEquals("<abc@example.com>", message.internetMessageId());
        assertTrue(message.bodyText().contains("Hi there"));
    }

    @Test
    void unfoldsHeadersAndPicksMultipartBody() {
        String eml = "From: Bob <bob@example.com>\n"
                + "Subject: Long\n"
                + " subject here\n"
                + "Content-Type: multipart/mixed; boundary=\"b1\"\n"
                + "\n"
                + "--b1\n"
                + "Content-Type: text/plain\n"
                + "\n"
                + "Plain body\n"
                + "--b1\n"
                + "Content-Type: text/html\n"
                + "\n"
                + "<p>HTML body</p>\n"
                + "--b1--\n";
        Message message = EmlMessageParser.parse(eml.getBytes(StandardCharsets.UTF_8), "a1", "inbox", "m2");
        assertEquals("Long subject here", message.subject());
        assertTrue(message.bodyText().contains("Plain body"));
    }
}
