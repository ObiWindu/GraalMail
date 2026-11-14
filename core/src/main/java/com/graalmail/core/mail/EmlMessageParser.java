/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.mail;

import com.graalmail.core.domain.Address;
import com.graalmail.core.domain.Attachment;
import com.graalmail.core.domain.Message;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal RFC 822 import for {@code .eml} files.
 *
 * <p>Handles plain single-part messages and multipart boundaries well enough
 * to rescue a subject, sender, date and a readable body. Anything fancier
 * stays on the raw bytes; this is a local import, not a full MIME stack.</p>
 */
public final class EmlMessageParser {

    private EmlMessageParser() {
    }

    /**
     * Parses raw {@code .eml} bytes into a message shell.
     *
     * @param raw the file bytes
     * @param accountId the account the import lands in
     * @param folderId the folder the import lands in
     * @param id the local id for the imported message
     * @return the parsed message
     */
    public static Message parse(byte[] raw, String accountId, String folderId, String id) {
        String text = raw == null ? "" : new String(raw, StandardCharsets.UTF_8);
        String headerSection = text;
        String bodySection = "";
        int split = text.indexOf("\r\n\r\n");
        int splitLf = text.indexOf("\n\n");
        if (split >= 0) {
            headerSection = text.substring(0, split);
            bodySection = text.substring(split + 4);
        } else if (splitLf >= 0) {
            headerSection = text.substring(0, splitLf);
            bodySection = text.substring(splitLf + 2);
        }
        List<Message.Header> headers = parseHeaders(headerSection);
        String subject = header(headers, "subject");
        String from = header(headers, "from");
        String date = header(headers, "date");
        String messageId = header(headers, "message-id");
        String contentType = header(headers, "content-type");

        String bodyText = bodySection;
        String bodyHtml = null;
        String boundary = boundaryOf(contentType);
        List<Attachment> attachments = new ArrayList<>();
        if (boundary != null) {
            Part part = pickBodyPart(bodySection, boundary);
            bodyText = part.text();
            bodyHtml = part.html();
            attachments = part.attachments();
        } else if (contentType.toLowerCase(Locale.ROOT).contains("text/html")) {
            bodyHtml = bodySection;
            bodyText = bodySection.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        }

        Address fromAddress = Address.parse(from.isBlank() ? "unknown@local" : from);
        Instant sentAt = parseDate(date);
        return Message.builder(id == null || id.isBlank() ? "imported-" + System.currentTimeMillis() : id)
                .accountId(accountId == null ? "" : accountId)
                .folderId(folderId == null ? "" : folderId)
                .internetMessageId(messageId)
                .subject(subject)
                .from(fromAddress)
                .date(sentAt)
                .snippet(snippet(bodyText))
                .unread(false)
                .bodyText(bodyText)
                .bodyHtml(bodyHtml)
                .attachments(attachments)
                .attachment(!attachments.isEmpty())
                .headers(headers)
                .size(raw == null ? 0 : raw.length)
                .build();
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 140 ? flat : flat.substring(0, 139) + "…";
    }

    static List<Message.Header> parseHeaders(String section) {
        List<Message.Header> headers = new ArrayList<>();
        StringBuilder currentName = new StringBuilder();
        StringBuilder currentValue = new StringBuilder();
        boolean hasCurrent = false;
        for (String line : section.split("\r?\n")) {
            if (line.isEmpty()) {
                continue;
            }
            char first = line.charAt(0);
            if ((first == ' ' || first == '\t') && hasCurrent) {
                currentValue.append(' ').append(line.trim());
                continue;
            }
            if (hasCurrent) {
                headers.add(new Message.Header(currentName.toString(), currentValue.toString().trim()));
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                hasCurrent = false;
                currentName.setLength(0);
                currentValue.setLength(0);
                continue;
            }
            currentName.setLength(0);
            currentValue.setLength(0);
            currentName.append(line.substring(0, colon).trim());
            currentValue.append(line.substring(colon + 1).trim());
            hasCurrent = true;
        }
        if (hasCurrent) {
            headers.add(new Message.Header(currentName.toString(), currentValue.toString().trim()));
        }
        return headers;
    }

    private static String header(List<Message.Header> headers, String name) {
        for (Message.Header header : headers) {
            if (header.name().equalsIgnoreCase(name)) {
                return header.value();
            }
        }
        return "";
    }

    private static String boundaryOf(String contentType) {
        if (contentType == null) {
            return null;
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        int index = lower.indexOf("boundary=");
        if (index < 0) {
            return null;
        }
        String boundary = contentType.substring(index + "boundary=".length()).trim();
        if (boundary.startsWith("\"")) {
            int end = boundary.indexOf('"', 1);
            boundary = end > 0 ? boundary.substring(1, end) : boundary.substring(1);
        } else {
            int end = boundary.indexOf(';');
            boundary = end > 0 ? boundary.substring(0, end).trim() : boundary.trim();
        }
        return boundary.isEmpty() ? null : boundary;
    }

    private record Part(String text, String html, List<Attachment> attachments) {
    }

    private static Part pickBodyPart(String body, String boundary) {
        String delimiter = "--" + boundary;
        String text = "";
        String html = "";
        List<Attachment> attachments = new ArrayList<>();
        for (String rawPart : body.split(java.util.regex.Pattern.quote(delimiter))) {
            String part = rawPart.trim();
            if (part.isEmpty() || part.equals("--")) {
                continue;
            }
            int headerEnd = part.indexOf("\r\n\r\n");
            if (headerEnd < 0) {
                headerEnd = part.indexOf("\n\n");
            }
            String partHeaders = headerEnd >= 0 ? part.substring(0, headerEnd) : "";
            String partBody = headerEnd >= 0 ? part.substring(headerEnd).trim() : part;
            if (partBody.endsWith("--")) {
                partBody = partBody.substring(0, partBody.length() - 2).trim();
            }
            String lower = partHeaders.toLowerCase(Locale.ROOT);
            String disposition = headerValue(partHeaders, "content-disposition");
            String filename = filenameOf(disposition);
            if (!filename.isEmpty()) {
                attachments.add(new Attachment(filename, filename, "application/octet-stream",
                        partBody.length(), false, ""));
                continue;
            }
            if (lower.contains("text/html") && html.isEmpty()) {
                html = partBody;
            } else if (lower.contains("text/plain") && text.isEmpty()) {
                text = partBody;
            } else if (text.isEmpty() && !partBody.isEmpty()) {
                text = partBody;
            }
        }
        if (text.isEmpty() && !html.isEmpty()) {
            text = html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        }
        return new Part(text, html.isEmpty() ? null : html, attachments);
    }

    private static String headerValue(String headers, String name) {
        for (String line : headers.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return "";
    }

    private static String filenameOf(String disposition) {
        if (disposition == null || disposition.isBlank()) {
            return "";
        }
        String lower = disposition.toLowerCase(Locale.ROOT);
        int index = lower.indexOf("filename=");
        if (index < 0) {
            return "";
        }
        String filename = disposition.substring(index + "filename=".length()).trim();
        if (filename.startsWith("\"")) {
            int end = filename.indexOf('"', 1);
            return end > 0 ? filename.substring(1, end) : filename.substring(1);
        }
        int end = filename.indexOf(';');
        return (end > 0 ? filename.substring(0, end) : filename).trim();
    }

    static Instant parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] patterns = {
            "EEE, d MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "d MMM yyyy HH:mm:ss Z"
        };
        for (String pattern : patterns) {
            try {
                return ZonedDateTime.parse(value.trim(),
                        DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)).toInstant();
            } catch (RuntimeException ignored) {
            }
        }
        try {
            return Instant.parse(value.trim());
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
