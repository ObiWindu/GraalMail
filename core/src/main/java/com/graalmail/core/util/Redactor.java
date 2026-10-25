/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.util;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes secrets from text before it reaches a log file. Applied to every
 * logged string so that tokens, passwords and message bodies never leak.
 */
public final class Redactor {

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(?i)(password|passwd|pwd)\\s*[=:]\\s*\\S+"),
            Pattern.compile("(?i)(access[_-]?token|refresh[_-]?token|id[_-]?token|client[_-]?secret"
                    + "|authorization|bearer)\\s*[=:]\\s*\\S+"),
            Pattern.compile("(?i)(api[_-]?key|secret|signature)\\s*[=:]\\s*\\S+"),
            Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{8,}"),
            Pattern.compile("(?i)\\bAuthorization\\s*[:=]\\s*\\S+(\\s+\\S+)?"),
            Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{5,}"),
            Pattern.compile("(?i)\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"));

    private static final String MASK = "[redacted]";

    private Redactor() {
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (Pattern pattern : PATTERNS) {
            Matcher matcher = pattern.matcher(result);
            result = matcher.replaceAll(m -> Matcher.quoteReplacement(replaceGroup(m)));
        }
        return result;
    }

    private static String replaceGroup(java.util.regex.MatchResult matcher) {
        String group = matcher.group();
        int idx = group.indexOf('=');
        if (idx < 0) {
            idx = group.indexOf(':');
        }
        if (idx < 0) {
            return MASK;
        }
        return group.substring(0, idx + 1) + " " + MASK;
    }
}
