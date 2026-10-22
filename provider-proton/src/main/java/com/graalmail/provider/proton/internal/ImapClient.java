/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Minimal IMAP4rev1 client used to talk to the local Proton Mail Bridge.
 *
 * <p>The client speaks to a local endpoint only and always uses a configured
 * TLS context; certificate and hostname verification are never disabled.</p>
 */
public final class ImapClient implements AutoCloseable {

    private final String host;
    private final int port;
    private final SSLContext sslContext;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final boolean startTls;

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private int tagCounter;
    private String selectedMailbox = "";

    public ImapClient(String host, int port, SSLContext sslContext, boolean startTls,
                      int connectTimeoutMillis, int readTimeoutMillis) {
        this.host = host;
        this.port = port;
        this.sslContext = sslContext;
        this.startTls = startTls;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    /** Opens the connection and performs the TLS handshake. */
    public void connect() throws IOException {
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
        plain.setSoTimeout(readTimeoutMillis);
        if (!startTls) {
            socket = upgrade(plain);
            in = socket.getInputStream();
            out = socket.getOutputStream();
            return;
        }
        in = plain.getInputStream();
        out = plain.getOutputStream();
        String greeting = readLine();
        if (greeting == null || !greeting.startsWith("* OK")) {
            plain.close();
            throw new IOException("The service on " + host + ":" + port
                    + " did not answer with an IMAP greeting");
        }
        Response response = command("STARTTLS");
        if (!response.isOk()) {
            plain.close();
            throw new IOException("Proton Mail Bridge refused STARTTLS");
        }
        socket = upgrade(plain);
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    private Socket upgrade(Socket plain) throws IOException {
        try {
            SSLSocketFactory factory = sslContext.getSocketFactory();
            SSLSocket ssl = (SSLSocket) factory.createSocket(plain, host, port, true);
            ssl.setSoTimeout(readTimeoutMillis);
            javax.net.ssl.SSLParameters params = ssl.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(params);
            ssl.startHandshake();
            return ssl;
        } catch (IOException e) {
            plain.close();
            throw e;
        }
    }

    /** Authenticates with the bridge credentials. */
    public void login(String username, String password) throws IOException {
        Response response = command("LOGIN " + quote(username) + " " + quote(password));
        if (!response.isOk()) {
            throw new IOException("Proton Mail Bridge rejected the credentials");
        }
    }

    /** Lists the mailboxes exposed by the bridge. */
    public List<ImapFolder> listFolders() throws IOException {
        Response response = command("LIST \"\" \"*\"");
        List<ImapFolder> folders = new ArrayList<>();
        for (String line : response.lines()) {
            if (!line.startsWith("* LIST")) {
                continue;
            }
            String rest = line.substring("* LIST".length()).trim();
            String[] parsed = splitListLine(rest);
            if (parsed.length == 0) {
                continue;
            }
            String delimiter = parsed[0].equals("NIL") ? "/" : parsed[0];
            String name = parsed.length >= 2 ? parsed[1] : "";
            if (name.isEmpty()) {
                continue;
            }
            folders.add(new ImapFolder(name, delimiter));
        }
        return folders;
    }

    /** Selects a mailbox and returns the number of messages it holds. */
    public int select(String mailbox) throws IOException {
        Response response = command("SELECT " + quote(mailbox));
        if (!response.isOk()) {
            throw new IOException("Unable to select the mailbox " + mailbox);
        }
        selectedMailbox = mailbox;
        int exists = 0;
        for (String line : response.lines()) {
            if (line.startsWith("*") && line.contains("EXISTS")) {
                String[] parts = line.split("\\s+");
                try {
                    exists = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {
                    exists = 0;
                }
            }
        }
        return exists;
    }

    /** Returns the total and unseen counters of a mailbox. */
    public int[] status(String mailbox) throws IOException {
        Response response = command("STATUS " + quote(mailbox) + " (MESSAGES UNSEEN)");
        for (String line : response.lines()) {
            if (line.startsWith("* STATUS")) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("MESSAGES (\\d+) UNSEEN (\\d+)").matcher(line);
                if (m.find()) {
                    return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
                }
            }
        }
        return new int[]{0, 0};
    }

    /** Fetches headers of a range of messages in the selected mailbox. */
    public List<ImapEnvelope> fetchEnvelopes(int from, int to) throws IOException {
        List<ImapEnvelope> envelopes = new ArrayList<>();
        if (to < from) {
            return envelopes;
        }
        Response response = command("FETCH " + from + ":" + to
                + " (UID FLAGS INTERNALDATE RFC822.SIZE BODY.PEEK[HEADER.FIELDS "
                + "(FROM TO CC BCTO REPLY-TO SUBJECT DATE MESSAGE-ID CONTENT-TYPE)])");
        for (Response.Element element : response.elements()) {
            ImapEnvelope envelope = new ImapEnvelope();
            envelope.sequence = parseSequence(element.line());
            if (envelope.sequence <= 0) {
                continue;
            }
            java.util.regex.Matcher uid = java.util.regex.Pattern
                    .compile("UID (\\d+)").matcher(element.line());
            if (uid.find()) {
                envelope.uid = Long.parseLong(uid.group(1));
            }
            java.util.regex.Matcher flags = java.util.regex.Pattern
                    .compile("FLAGS \\(([^)]*)\\)").matcher(element.line());
            if (flags.find()) {
                envelope.flags = flags.group(1).trim();
            }
            java.util.regex.Matcher size = java.util.regex.Pattern
                    .compile("RFC822.SIZE (\\d+)").matcher(element.line());
            if (size.find()) {
                envelope.size = Long.parseLong(size.group(1));
            }
            java.util.regex.Matcher date = java.util.regex.Pattern
                    .compile("INTERNALDATE \"([^\"]*)\"").matcher(element.line());
            if (date.find()) {
                envelope.internalDate = date.group(1);
            }
            envelope.headerBlock = element.literal == null ? "" : new String(element.literal,
                    StandardCharsets.ISO_8859_1);
            envelopes.add(envelope);
        }
        return envelopes;
    }

    /** Fetches the full raw message of a sequence number. */
    public byte[] fetchRaw(int sequence) throws IOException {
        Response response = command("FETCH " + sequence + " (BODY.PEEK[])");
        for (Response.Element element : response.elements()) {
            if (element.literal != null) {
                return element.literal;
            }
        }
        throw new IOException("The message body could not be read");
    }

    /** Fetches a single MIME part of a message. */
    public byte[] fetchPart(int sequence, String part) throws IOException {
        Response response = command("FETCH " + sequence + " (BODY.PEEK[" + part + "])");
        for (Response.Element element : response.elements()) {
            if (element.literal != null) {
                return element.literal;
            }
        }
        throw new IOException("The attachment part could not be read");
    }

    /** Adds or removes flags of a message. */
    public void storeFlags(int sequence, boolean add, String... flags) throws IOException {
        StringBuilder sb = new StringBuilder("STORE ").append(sequence)
                .append(add ? " +FLAGS (" : " -FLAGS (");
        for (int i = 0; i < flags.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(flags[i]);
        }
        sb.append(')');
        Response response = command(sb.toString());
        if (!response.isOk()) {
            throw new IOException("Unable to change the message flags");
        }
    }

    /** Copies a message into another mailbox. */
    public void copy(int sequence, String target) throws IOException {
        Response response = command("COPY " + sequence + " " + quote(target));
        if (!response.isOk()) {
            throw new IOException("Unable to copy the message to " + target);
        }
    }

    /** Permanently removes the messages marked as deleted. */
    public void expunge() throws IOException {
        Response response = command("EXPUNGE");
        if (!response.isOk()) {
            throw new IOException("Unable to expunge the mailbox");
        }
    }

    /** Runs an IMAP SEARCH with the given criteria. */
    public List<Long> search(String criteria) throws IOException {
        Response response = command("SEARCH " + criteria);
        List<Long> uids = new ArrayList<>();
        for (String line : response.lines()) {
            if (line.startsWith("* SEARCH")) {
                String rest = line.substring("* SEARCH".length()).trim();
                for (String token : rest.split("\\s+")) {
                    if (token.isEmpty()) {
                        continue;
                    }
                    try {
                        uids.add(Long.parseLong(token));
                    } catch (NumberFormatException ignored) {
                        // not a uid
                    }
                }
            }
        }
        return uids;
    }

    /** Appends a message to a mailbox, used for server side drafts. */
    public void append(String mailbox, byte[] message) throws IOException {
        String tag = nextTag();
        StringBuilder head = new StringBuilder(tag).append(" APPEND ").append(quote(mailbox))
                .append(" {").append(message.length).append("}\r\n");
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(message);
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        Response response = readResponse(tag);
        if (!response.isOk()) {
            throw new IOException("Unable to append the draft");
        }
    }

    /** Closes the connection politely. */
    public void logout() {
        try {
            if (out != null) {
                command("LOGOUT");
            }
        } catch (IOException ignored) {
            // the connection is closed anyway
        }
    }

    @Override
    public void close() {
        try {
            logout();
        } finally {
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    public String selectedMailbox() {
        return selectedMailbox;
    }

    private Response command(String command) throws IOException {
        String tag = nextTag();
        out.write((tag + " " + command + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        return readResponse(tag);
    }

    private String nextTag() {
        tagCounter++;
        return String.format("a%03d", tagCounter);
    }

    private Response readResponse(String tag) throws IOException {
        Response response = new Response();
        while (true) {
            String line = readLine();
            if (line == null) {
                throw new IOException("The IMAP connection was closed by the bridge");
            }
            byte[] literal = readLiteralIfPresent(line);
            response.add(line, literal);
            if (line.startsWith(tag + " ")) {
                return response;
            }
        }
    }

    private byte[] readLiteralIfPresent(String line) throws IOException {
        int marker = line.lastIndexOf('{');
        if (marker < 0 || !line.endsWith("}")) {
            return null;
        }
        String number = line.substring(marker + 1, line.length() - 1);
        int length;
        try {
            length = Integer.parseInt(number.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        byte[] buffer = readFully(length);
        int next = in.read();
        if (next == '\r') {
            in.read();
        }
        return buffer;
    }

    private byte[] readFully(int length) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.max(length, 32));
        byte[] chunk = new byte[Math.min(Math.max(length, 1), 8192)];
        int remaining = length;
        while (remaining > 0) {
            int read = in.read(chunk, 0, Math.min(chunk.length, remaining));
            if (read < 0) {
                throw new IOException("The IMAP connection ended inside a literal");
            }
            buffer.write(chunk, 0, read);
            remaining -= read;
        }
        return buffer.toByteArray();
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                buffer.write(c);
            }
        }
        if (c == -1 && buffer.size() == 0) {
            return null;
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    static String quote(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.append('"').toString();
    }

    private static int parseSequence(String line) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^\\*? ?(\\d+) FETCH").matcher(line);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static String[] splitListLine(String rest) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < rest.length()) {
            while (i < rest.length() && rest.charAt(i) == ' ') {
                i++;
            }
            if (i >= rest.length()) {
                break;
            }
            if (rest.charAt(i) == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < rest.length()) {
                    char c = rest.charAt(i++);
                    if (c == '\\' && i < rest.length()) {
                        sb.append(rest.charAt(i++));
                    } else if (c == '"') {
                        break;
                    } else {
                        sb.append(c);
                    }
                }
                tokens.add(sb.toString());
            } else {
                int start = i;
                while (i < rest.length() && rest.charAt(i) != ' ') {
                    i++;
                }
                tokens.add(rest.substring(start, i));
            }
        }
        return tokens.toArray(new String[0]);
    }

    /** A mailbox exposed by the bridge. */
    public static final class ImapFolder {
        private final String name;
        private final String delimiter;

        ImapFolder(String name, String delimiter) {
            this.name = name;
            this.delimiter = delimiter;
        }

        public String name() { return name; }
        public String delimiter() { return delimiter; }
    }

    /** Header information of a message. */
    public static final class ImapEnvelope {
        private int sequence;
        private long uid;
        private String flags = "";
        private long size;
        private String internalDate = "";
        private String headerBlock = "";

        public int sequence() { return sequence; }
        public long uid() { return uid; }
        public String flags() { return flags; }
        public long size() { return size; }
        public String internalDate() { return internalDate; }
        public String headerBlock() { return headerBlock; }
        public boolean hasFlag(String flag) {
            return flags.toUpperCase().contains("\\" + flag.toUpperCase());
        }
    }

    /** A parsed IMAP response. */
    public static final class Response {

        private final List<Element> elements = new ArrayList<>();

        void add(String line, byte[] literal) {
            elements.add(new Element(line, literal));
        }

        public List<String> lines() {
            List<String> lines = new ArrayList<>();
            for (Element element : elements) {
                lines.add(element.line);
            }
            return lines;
        }

        public List<Element> elements() {
            return elements;
        }

        public boolean isOk() {
            for (Element element : elements) {
                if (element.line.matches("^[A-Za-z0-9]+ OK.*")) {
                    return true;
                }
            }
            return false;
        }

        public String statusText() {
            for (Element element : elements) {
                if (element.line.matches("^[A-Za-z0-9]+ (NO|BAD) .*")) {
                    return element.line;
                }
            }
            return "";
        }

        /** A response element: a line plus an optional literal payload. */
        public static final class Element {
            private final String line;
            private final byte[] literal;

            Element(String line, byte[] literal) {
                this.line = line;
                this.literal = literal;
            }

            public String line() { return line; }
            public byte[] literal() { return literal; }
        }
    }
}
