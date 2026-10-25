/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton.internal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Small MIME reader for the messages the bridge returns. It extracts the
 * plain text and HTML body plus the attachment descriptors; binary content is
 * kept as is and fetched on demand.
 */
public final class MimeParser {

    private MimeParser() {
    }

    /** Parses a raw message. */
    public static ParsedMessage parse(byte[] raw) {
        ParsedMessage message = new ParsedMessage();
        byte[] body = raw;
        int separator = indexOfBlankLine(raw);
        if (separator >= 0) {
            String headerBlock = new String(raw, 0, separator, StandardCharsets.ISO_8859_1);
            body = new byte[raw.length - separator];
            System.arraycopy(raw, separator, body, 0, body.length);
            message.headers = parseHeaders(headerBlock);
        } else {
            message.headers = new LinkedHashMap<>();
        }
        message.contentType = contentType(message.headers);
        if (message.contentType.startsWith("multipart/")) {
            String boundary = parameter(message.headers, "content-type", "boundary");
            List<byte[]> parts = splitMultipart(body, boundary);
            int index = 1;
            for (byte[] part : parts) {
                parsePart(part, String.valueOf(index), message);
                index++;
            }
        } else {
            parseBody(body, message.contentType, message.headers, message);
        }
        return message;
    }

    private static void parsePart(byte[] raw, String path, ParsedMessage message) {
        byte[] body = raw;
        Map<String, String> headers = new LinkedHashMap<>();
        int separator = indexOfBlankLine(raw);
        if (separator >= 0) {
            headers = parseHeaders(new String(raw, 0, separator, StandardCharsets.ISO_8859_1));
            body = new byte[raw.length - separator];
            System.arraycopy(raw, separator, body, 0, body.length);
        }
        String contentType = contentType(headers);
        if (contentType.startsWith("multipart/")) {
            String boundary = parameter(headers, "content-type", "boundary");
            List<byte[]> parts = splitMultipart(body, boundary);
            int index = 1;
            for (byte[] part : parts) {
                parsePart(part, path + "." + index, message);
                index++;
            }
            return;
        }
        String disposition = headers.getOrDefault("content-disposition", "").toLowerCase(Locale.ROOT);
        boolean isAttachment = disposition.startsWith("attachment")
                || (!contentType.startsWith("text/") && !contentType.startsWith("message/"));
        String fileName = parameter(headers, "content-disposition", "filename");
        if (fileName.isEmpty()) {
            fileName = parameter(headers, "content-type", "name");
        }
        if (isAttachment) {
            byte[] decoded = decodeBody(body, headers);
            message.attachments.add(new Part(path, fileName.isEmpty() ? "attachment" : fileName,
                    contentType, decoded.length, disposition.startsWith("inline"),
                    headers.getOrDefault("content-id", ""), decoded));
            return;
        }
        parseBody(body, contentType, headers, message);
    }

    private static void parseBody(byte[] body, String contentType, Map<String, String> headers,
                                  ParsedMessage message) {
        byte[] decoded = decodeBody(body, headers);
        if (contentType.startsWith("text/html")) {
            message.bodyHtml = new String(decoded, StandardCharsets.UTF_8);
        } else if (contentType.startsWith("text/")) {
            message.bodyText = new String(decoded, StandardCharsets.UTF_8);
        }
    }

    private static byte[] decodeBody(byte[] body, Map<String, String> headers) {
        String encoding = headers.getOrDefault("content-transfer-encoding", "")
                .trim().toLowerCase(Locale.ROOT);
        if (encoding.equals("base64")) {
            try {
                return Base64.getMimeDecoder().decode(body);
            } catch (IllegalArgumentException e) {
                return body;
            }
        }
        if (encoding.equals("quoted-printable")) {
            return decodeQuotedPrintable(body);
        }
        return body;
    }

    private static byte[] decodeQuotedPrintable(byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length);
        for (int i = 0; i < body.length; i++) {
            char c = (char) (body[i] & 0xFF);
            if (c == '=' && i + 2 < body.length) {
                String hex = new String(body, i + 1, 2, StandardCharsets.US_ASCII);
                try {
                    out.write(Integer.parseInt(hex, 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
            out.write(body[i]);
        }
        return out.toByteArray();
    }

    private static int indexOfBlankLine(byte[] raw) {
        for (int i = 0; i + 1 < raw.length; i++) {
            if (raw[i] == '\n' && raw[i + 1] == '\n') {
                return i + 2;
            }
            if (i + 3 < raw.length && raw[i] == '\r' && raw[i + 1] == '\n'
                    && raw[i + 2] == '\r' && raw[i + 3] == '\n') {
                return i + 4;
            }
        }
        return -1;
    }

    public static Map<String, String> parseHeaders(String block) {
        Map<String, String> headers = new LinkedHashMap<>();
        String[] lines = block.split("\r?\n");
        String name = null;
        StringBuilder value = new StringBuilder();
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            if ((line.startsWith(" ") || line.startsWith("\t")) && name != null) {
                value.append(' ').append(line.trim());
                continue;
            }
            if (name != null) {
                headers.putIfAbsent(name, value.toString());
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                name = null;
                value = new StringBuilder();
                continue;
            }
            name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            value = new StringBuilder(line.substring(colon + 1).trim());
        }
        if (name != null) {
            headers.putIfAbsent(name, value.toString());
        }
        return headers;
    }

    static String contentType(Map<String, String> headers) {
        String raw = headers.getOrDefault("content-type", "text/plain");
        int semicolon = raw.indexOf(';');
        return (semicolon < 0 ? raw : raw.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
    }

    static String parameter(Map<String, String> headers, String header, String name) {
        String raw = headers.get(header);
        if (raw == null) {
            return "";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?i)" + java.util.regex.Pattern.quote(name) + "=\"?([^\";]*)\"?")
                .matcher(raw);
        if (m.find()) {
            return m.group(1).trim();
        }
        return "";
    }

    private static List<byte[]> splitMultipart(byte[] body, String boundary) {
        List<byte[]> parts = new ArrayList<>();
        if (boundary == null || boundary.isEmpty()) {
            return parts;
        }
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        List<Integer> marks = new ArrayList<>();
        int index = indexOf(body, delimiter, 0);
        while (index >= 0) {
            marks.add(index);
            index = indexOf(body, delimiter, index + delimiter.length);
        }
        for (int i = 0; i + 1 < marks.size(); i++) {
            int start = marks.get(i) + delimiter.length;
            int end = marks.get(i + 1);
            if (start < end) {
                while (start < end && (body[start] == '\r' || body[start] == '\n')) {
                    start++;
                }
                parts.add(java.util.Arrays.copyOfRange(body, start, end));
            }
        }
        return parts;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = Math.max(from, 0); i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** A parsed message. */
    public static final class ParsedMessage {
        private Map<String, String> headers = new LinkedHashMap<>();
        private String contentType = "text/plain";
        private String bodyText = "";
        private String bodyHtml = "";
        private final List<Part> attachments = new ArrayList<>();

        public Map<String, String> headers() { return headers; }
        public String contentType() { return contentType; }
        public String bodyText() { return bodyText; }
        public String bodyHtml() { return bodyHtml; }
        public List<Part> attachments() { return attachments; }

        public String header(String name) {
            return headers.getOrDefault(name.toLowerCase(Locale.ROOT), "");
        }
    }

    /** One attachment of a parsed message. */
    public static final class Part {
        private final String path;
        private final String fileName;
        private final String mimeType;
        private final long size;
        private final boolean inline;
        private final String contentId;
        private final byte[] content;

        Part(String path, String fileName, String mimeType, long size, boolean inline,
             String contentId, byte[] content) {
            this.path = path;
            this.fileName = fileName;
            this.mimeType = mimeType;
            this.size = size;
            this.inline = inline;
            this.contentId = contentId;
            this.content = content;
        }

        public String path() { return path; }
        public String fileName() { return fileName; }
        public String mimeType() { return mimeType; }
        public long size() { return size; }
        public boolean inline() { return inline; }
        public String contentId() { return contentId; }
        public byte[] content() { return content; }
    }
}
