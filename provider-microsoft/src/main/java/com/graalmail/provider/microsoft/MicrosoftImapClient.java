/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.microsoft;

import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A small IMAP4rev1 client that authenticates with the XOAUTH2 SASL
 * mechanism, as required by Outlook.com and Microsoft 365.
 *
 * <p>Only the commands the mail client needs are implemented: folder listing,
 * message fetching, flag updates, search, copy/move, append and expunge. The
 * client speaks IMAP directly so that the XOAUTH2 exchange stays under our
 * control and the access token never leaves the TLS connection.</p>
 */
public final class MicrosoftImapClient implements AutoCloseable {

    /** Folder attributes that mark a folder as containing messages. */
    public static final String ATTR_HAS_CHILDREN = "\\HasChildren";
    /** Folder attribute that marks a folder as having no children. */
    public static final String ATTR_HAS_NO_CHILDREN = "\\HasNoChildren";
    /** The IMAP \Seen flag. */
    public static final String FLAG_SEEN = "\\Seen";
    /** The IMAP \Flagged flag. */
    public static final String FLAG_FLAGGED = "\\Flagged";
    /** The IMAP \Deleted flag. */
    public static final String FLAG_DELETED = "\\Deleted";
    /** The IMAP \Draft flag. */
    public static final String FLAG_DRAFT = "\\Draft";

    private final String host;
    private final int port;
    private final String user;

    private SSLSocket socket;
    private InputStream in;
    private OutputStream out;
    private int tagCounter;
    private int selectedFolderId = -1;

    /**
     * Creates a client.
     *
     * @param host the IMAP host
     * @param port the IMAP port
     * @param user the account email address
     */
    public MicrosoftImapClient(String host, int port, String user) {
        this.host = host;
        this.port = port;
        this.user = user;
    }

    /**
     * Opens the TLS connection and performs the XOAUTH2 authentication.
     *
     * @param accessToken the OAuth access token
     * @throws ProviderException when the connection or the authentication fails
     */
    public void connectAndAuthenticate(String accessToken) {
        try {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            socket = (SSLSocket) factory.createSocket(host, port);
            socket.setSoTimeout(60_000);
            in = socket.getInputStream();
            out = socket.getOutputStream();
            String greeting = readLine();
            if (greeting == null || !greeting.startsWith("* OK")) {
                throw MicrosoftErrorMapper.classify("Unexpected IMAP greeting: " + greeting,
                        ErrorCode.PROTOCOL_ERROR);
            }
            authenticateXoauth2(accessToken);
        } catch (ProviderException e) {
            closeQuietly();
            throw e;
        } catch (IOException e) {
            closeQuietly();
            throw ProviderException.network("Could not connect to the Microsoft IMAP server: "
                    + e.getMessage(), e);
        }
    }

    private void authenticateXoauth2(String accessToken) {
        String initial = "user=" + user + "\u0001auth=Bearer " + accessToken + "\u0001\u0001";
        String encoded = Base64.getEncoder().encodeToString(initial.getBytes(StandardCharsets.UTF_8));
        sendRaw("AUTHENTICATE XOAUTH2 " + encoded);
        String line = readLine();
        if (line == null) {
            throw ProviderException.network("The Microsoft IMAP server closed the connection", null);
        }
        if (line.startsWith("+")) {
            // The server asks for more data; an empty line reports the error.
            sendRaw("");
            line = readLine();
        }
        if (line == null || !line.contains("OK")) {
            throw MicrosoftErrorMapper.classify(
                    "Microsoft rejected the IMAP authentication: " + line, ErrorCode.AUTHENTICATION_FAILED);
        }
    }

    /**
     * Lists the mailbox folders.
     *
     * @return the folder names as reported by the server
     * @throws ProviderException when the command fails
     */
    public List<String> listFolders() {
        Response response = command("LIST \"\" *");
        List<String> folders = new ArrayList<>();
        for (Part part : response.parts()) {
            String text = part.text();
            if (!text.startsWith("* LIST")) {
                continue;
            }
            String name = parseListName(text);
            if (name != null && !name.isBlank()) {
                folders.add(name);
            }
        }
        return folders;
    }

    /**
     * Selects a folder and returns the number of messages it holds.
     *
     * @param folder the folder name
     * @return the message count reported by the server
     * @throws ProviderException when the folder cannot be selected
     */
    public int select(String folder) {
        Response response = command("SELECT " + quote(folder));
        int count = 0;
        for (Part part : response.parts()) {
            String text = part.text();
            if (text.startsWith("* ") && text.contains(" EXISTS")) {
                String digits = text.replaceAll("[^0-9]", "");
                if (!digits.isEmpty()) {
                    count = Integer.parseInt(digits);
                }
            }
        }
        selectedFolderId = count;
        return count;
    }

    /**
     * Searches a folder for the given IMAP search criteria.
     *
     * @param folder the folder name
     * @param criteria the raw IMAP SEARCH criteria
     * @return the matching UIDs
     * @throws ProviderException when the command fails
     */
    public List<Long> search(String folder, String criteria) {
        select(folder);
        Response response = command("UID SEARCH " + criteria);
        List<Long> uids = new ArrayList<>();
        for (Part part : response.parts()) {
            String text = part.text().trim();
            if (text.startsWith("* SEARCH")) {
                String value = text.substring("* SEARCH".length()).trim();
                for (String token : value.split("\\s+")) {
                    if (!token.isBlank()) {
                        try {
                            uids.add(Long.parseLong(token));
                        } catch (NumberFormatException ignored) {
                            // ignore malformed tokens
                        }
                    }
                }
            }
        }
        return uids;
    }

    /**
     * Fetches the raw message with the given UID.
     *
     * @param folder the folder name
     * @param uid the message UID
     * @return the raw RFC 822 message
     * @throws ProviderException when the message cannot be fetched
     */
    public byte[] fetchMessage(String folder, long uid) {
        return fetch(folder, uid, "FLAGS BODY.PEEK[]");
    }

    /**
     * Fetches parts of a message.
     *
     * @param folder the folder name
     * @param uid the message UID
     * @param items the IMAP FETCH items, for example {@code BODY.PEEK[HEADER]}
     * @return the raw payload returned by the server
     * @throws ProviderException when the message cannot be fetched
     */
    public byte[] fetch(String folder, long uid, String items) {
        select(folder);
        Response response = command("UID FETCH " + uid + " (" + items + ")");
        for (Part part : response.parts()) {
            if (part.literal() != null) {
                return part.literal();
            }
        }
        throw new ProviderException(ErrorCode.MESSAGE_NOT_FOUND,
                "The message could not be fetched from Microsoft.", false);
    }

    /**
     * Reads the flags of a message.
     *
     * @param folder the folder name
     * @param uid the message UID
     * @return the IMAP flags of the message
     * @throws ProviderException when the command fails
     */
    public List<String> flags(String folder, long uid) {
        select(folder);
        Response response = command("UID FETCH " + uid + " (FLAGS)");
        List<String> flags = new ArrayList<>();
        for (Part part : response.parts()) {
            int start = part.text().indexOf("FLAGS (");
            if (start < 0) {
                continue;
            }
            int end = part.text().indexOf(')', start);
            if (end < 0) {
                continue;
            }
            String value = part.text().substring(start + "FLAGS (".length(), end).trim();
            if (!value.isEmpty()) {
                for (String flag : value.split("\\s+")) {
                    flags.add(flag);
                }
            }
        }
        return flags;
    }

    /**
     * Adds or removes flags on a message.
     *
     * @param folder the folder name
     * @param uid the message UID
     * @param flags the flags to set
     * @param set true to add the flags, false to remove them
     * @throws ProviderException when the command fails
     */
    public void setFlags(String folder, long uid, List<String> flags, boolean set) {
        if (flags.isEmpty()) {
            return;
        }
        select(folder);
        StringBuilder sb = new StringBuilder("UID STORE ").append(uid)
                .append(set ? " +FLAGS (" : " -FLAGS (");
        for (int i = 0; i < flags.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(flags.get(i));
        }
        sb.append(')');
        command(sb.toString());
    }

    /**
     * Copies a message into another folder.
     *
     * @param folder the source folder
     * @param uid the message UID
     * @param target the target folder
     * @throws ProviderException when the command fails
     */
    public void copy(String folder, long uid, String target) {
        select(folder);
        command("UID COPY " + uid + " " + quote(target));
    }

    /**
     * Removes the \Deleted flag of all messages in the selected folder.
     *
     * @throws ProviderException when the command fails
     */
    public void expunge() {
        command("EXPUNGE");
    }

    /**
     * Appends a raw message to a folder.
     *
     * @param folder the target folder
     * @param rawMessage the raw RFC 822 message
     * @param flags the flags to set on the appended message
     * @throws ProviderException when the command fails
     */
    public void append(String folder, byte[] rawMessage, List<String> flags) {
        String tag = nextTag();
        StringBuilder sb = new StringBuilder(tag).append(" APPEND ").append(quote(folder));
        if (!flags.isEmpty()) {
            sb.append(" (").append(String.join(" ", flags)).append(')');
        }
        sb.append(" {").append(rawMessage.length).append("}");
        sendRaw(sb.toString());
        String line = readLine();
        if (line != null && line.startsWith("+")) {
            try {
                out.write(rawMessage);
                out.write('\r');
                out.write('\n');
                out.flush();
            } catch (IOException e) {
                throw ProviderException.network("Lost the connection to the Microsoft IMAP server", e);
            }
            line = readLine();
        }
        if (line == null || !line.contains("OK")) {
            throw MicrosoftErrorMapper.classify("Microsoft rejected the APPEND command: " + line,
                    ErrorCode.PROTOCOL_ERROR);
        }
    }

    /**
     * Sends the logout command and closes the connection.
     */
    @Override
    public void close() {
        try {
            if (out != null) {
                command("LOGOUT");
            }
        } catch (RuntimeException ignored) {
            // the connection is closed anyway
        } finally {
            closeQuietly();
        }
    }

    private void closeQuietly() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // nothing to do
        }
        socket = null;
        in = null;
        out = null;
    }

    private Response command(String commandLine) {
        String tag = nextTag();
        sendRaw(tag + " " + commandLine);
        List<Part> parts = new ArrayList<>();
        while (true) {
            Part part = readPart();
            if (part == null) {
                throw ProviderException.network("The Microsoft IMAP server closed the connection", null);
            }
            String text = part.text();
            if (text.startsWith(tag + " ")) {
                if (!text.contains("OK")) {
                    throw MicrosoftErrorMapper.classify(
                            "Microsoft rejected the IMAP command '" + commandLine + "': " + text,
                            ErrorCode.PROTOCOL_ERROR);
                }
                return new Response(parts);
            }
            parts.add(part);
        }
    }

    private void sendRaw(String line) {
        try {
            out.write(line.getBytes(StandardCharsets.UTF_8));
            out.write('\r');
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            throw ProviderException.network("Lost the connection to the Microsoft IMAP server", e);
        }
    }

    private String nextTag() {
        tagCounter++;
        return String.format(Locale.ROOT, "A%04d", tagCounter);
    }

    private String readLine() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int value;
        boolean any = false;
        try {
            while ((value = in.read()) != -1) {
                any = true;
                if (value == '\n') {
                    break;
                }
                if (value != '\r') {
                    buffer.write(value);
                }
            }
        } catch (IOException e) {
            throw ProviderException.network("Lost the connection to the Microsoft IMAP server", e);
        }
        if (!any) {
            return null;
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private Part readPart() {
        String line = readLine();
        if (line == null) {
            return null;
        }
        if (line.endsWith("}") && line.contains("{")) {
            int open = line.lastIndexOf('{');
            int close = line.lastIndexOf('}');
            if (open < close) {
                String sizeText = line.substring(open + 1, close);
                try {
                    int size = Integer.parseInt(sizeText.trim());
                    byte[] literal = readNBytes(size);
                    return new Part(line, literal);
                } catch (NumberFormatException ignored) {
                    return new Part(line, null);
                }
            }
        }
        return new Part(line, null);
    }

    private byte[] readNBytes(int size) {
        byte[] data = new byte[size];
        int offset = 0;
        try {
            while (offset < size) {
                int read = in.read(data, offset, size - offset);
                if (read < 0) {
                    throw new IOException("Unexpected end of stream");
                }
                offset += read;
            }
        } catch (IOException e) {
            throw ProviderException.network("Lost the connection to the Microsoft IMAP server", e);
        }
        // consume the trailing CRLF of the literal
        readLine();
        return data;
    }

    private static String parseListName(String text) {
        int quote = text.lastIndexOf('"');
        int open = text.lastIndexOf('(');
        int slash = text.lastIndexOf('/');
        int start = Math.max(quote, Math.max(open, slash)) + 1;
        if (start >= text.length()) {
            return null;
        }
        String name = text.substring(start).trim();
        if (name.startsWith("\"") && name.endsWith("\"") && name.length() > 1) {
            name = name.substring(1, name.length() - 1);
        }
        return name;
    }

    private static String quote(String value) {
        return "\"" + (value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")) + "\"";
    }

    /** A single response element, optionally followed by a literal payload. */
    private static final class Part {
        private final String text;
        private final byte[] literal;

        Part(String text, byte[] literal) {
            this.text = text;
            this.literal = literal;
        }

        String text() { return text; }

        byte[] literal() { return literal; }
    }

    /** A complete command response. */
    private record Response(List<Part> parts) {
    }
}
