/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.search;

import com.graalmail.core.domain.SearchQuery;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses Gmail-style search text into a {@link SearchQuery}.
 *
 * <p>Supported operators: {@code from:}, {@code to:}, {@code subject:},
 * {@code in:}, {@code account:}, {@code before:}/{@code after:}/{@code on:},
 * {@code has:attachment} and {@code is:unread|read|starred}. Quoted values
 * keep their spaces. Everything else becomes free text.</p>
 */
public final class SearchQueryParser {

    private SearchQueryParser() {
    }

    /**
     * Parses raw search text.
     *
     * @param raw the text from the search field, may be {@code null}
     * @return the structured query, never {@code null}
     */
    public static SearchQuery parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return SearchQuery.builder().build();
        }
        List<String> tokens = tokenize(raw);
        SearchQuery.Builder builder = SearchQuery.builder();
        StringBuilder text = new StringBuilder();
        List<String> folders = new ArrayList<>();
        List<String> accounts = new ArrayList<>();
        for (String token : tokens) {
            int colon = token.indexOf(':');
            if (colon > 0) {
                String key = token.substring(0, colon).toLowerCase(Locale.ROOT);
                String value = unquote(token.substring(colon + 1).trim());
                switch (key) {
                    case "from" -> builder.from(join(builder.build().from(), value));
                    case "to" -> builder.to(join(builder.build().to(), value));
                    case "subject" -> builder.subject(join(builder.build().subject(), value));
                    case "in", "folder" -> folders.add(value);
                    case "account", "acct" -> accounts.add(value);
                    case "before" -> builder.until(endOfDay(parseDate(value)));
                    case "after" -> builder.since(parseDate(value));
                    case "on", "date" -> {
                        Instant day = parseDate(value);
                        if (day != null) {
                            builder.since(day);
                            builder.until(endOfDay(day));
                        }
                    }
                    case "since" -> builder.since(parseDate(value));
                    case "until" -> builder.until(parseDate(value));
                    case "has" -> {
                        if (value.toLowerCase(Locale.ROOT).startsWith("attach")) {
                            builder.withAttachmentsOnly(true);
                        } else {
                            appendText(text, token);
                        }
                    }
                    case "is" -> {
                        String flag = value.toLowerCase(Locale.ROOT);
                        if ("unread".equals(flag)) {
                            builder.unreadOnly(true);
                        } else if ("starred".equals(flag) || "star".equals(flag) || "flagged".equals(flag)) {
                            builder.flaggedOnly(true);
                        } else if ("attachment".equals(flag) || "attachments".equals(flag)) {
                            builder.withAttachmentsOnly(true);
                        } else if (!"read".equals(flag)) {
                            appendText(text, token);
                        }
                    }
                    default -> appendText(text, token);
                }
                continue;
            }
            appendText(text, token);
        }
        builder.folderIds(folders);
        builder.accountIds(accounts);
        String free = text.toString().trim();
        if (!free.isEmpty()) {
            builder.text(free);
        }
        return builder.build();
    }

    private static String join(String existing, String value) {
        if (value.isEmpty()) {
            return existing == null ? "" : existing;
        }
        if (existing == null || existing.isBlank()) {
            return value;
        }
        return existing + " " + value;
    }

    private static void appendText(StringBuilder text, String token) {
        if (text.length() > 0) {
            text.append(' ');
        }
        text.append(unquote(token));
    }

    static List<String> tokenize(String raw) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                current.append(c);
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                current.append(c);
                continue;
            }
            if (Character.isWhitespace(c) && !inSingle && !inDouble) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
        }
        return trimmed;
    }

    static Instant parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        try {
            if (text.matches("\\d{4}-\\d{2}-\\d{2}")) {
                return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant();
            }
            if (text.matches("\\d{4}/\\d{2}/\\d{2}")) {
                String iso = text.replace('/', '-');
                return LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant();
            }
            return Instant.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Instant endOfDay(Instant start) {
        if (start == null) {
            return null;
        }
        return start.plusSeconds(24 * 60 * 60 - 1);
    }
}
