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
import java.net.Socket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A small SMTP submission client that authenticates with the XOAUTH2 SASL
 * mechanism against smtp.office365.com.
 *
 * <p>Port 587 is used with STARTTLS by default, port 465 uses implicit TLS.
 * The access token is only sent inside the TLS protected session.</p>
 */
public final class MicrosoftSmtpClient implements AutoCloseable {

    private final String host;
    private final int port;
    private final boolean implicitTls;
    private final String user;

    private Socket socket;
    private InputStream in;
    private OutputStream out;

    /**
     * Creates a client.
     *
     * @param host the SMTP host
     * @param port the SMTP port
     * @param implicitTls true for port 465, false for STARTTLS on port 587
     * @param user the account email address
     */
    public MicrosoftSmtpClient(String host, int port, boolean implicitTls, String user) {
        this.host = host;
        this.port = port;
        this.implicitTls = implicitTls;
        this.user = user;
    }

    /**
     * Connects, upgrades the connection and authenticates with XOAUTH2.
     *
     * @param accessToken the OAuth access token
     * @throws ProviderException when the connection or the authentication fails
     */
    public void connectAndAuthenticate(String accessToken) {
        try {
            if (implicitTls) {
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                socket = (SSLSocket) factory.createSocket(host, port);
            } else {
                socket = new Socket(host, port);
            }
            socket.setSoTimeout(60_000);
            in = socket.getInputStream();
            out = socket.getOutputStream();
            String greeting = readLine();
            if (greeting == null || !greeting.startsWith("220")) {
                throw MicrosoftErrorMapper.classify("Unexpected SMTP greeting: " + greeting,
                        ErrorCode.PROTOCOL_ERROR);
            }
            command("EHLO " + host, 250);
            if (!implicitTls) {
                command("STARTTLS", 220);
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                socket = factory.createSocket(socket, host, port, true);
                socket.setSoTimeout(60_000);
                in = socket.getInputStream();
                out = socket.getOutputStream();
                command("EHLO " + host, 250);
            }
            authenticate(accessToken);
        } catch (ProviderException e) {
            closeQuietly();
            throw e;
        } catch (IOException e) {
            closeQuietly();
            throw ProviderException.network("Could not connect to the Microsoft SMTP server: "
                    + e.getMessage(), e);
        }
    }

    private void authenticate(String accessToken) {
        String initial = "user=" + user + "\u0001auth=Bearer " + accessToken + "\u0001\u0001";
        String encoded = Base64.getEncoder().encodeToString(initial.getBytes(StandardCharsets.UTF_8));
        String response = commandRaw("AUTH XOAUTH2 " + encoded);
        if (!response.startsWith("235")) {
            throw MicrosoftErrorMapper.classify("Microsoft rejected the SMTP authentication: " + response,
                    ErrorCode.AUTHENTICATION_FAILED);
        }
    }

    /**
     * Sends a raw RFC 822 message.
     *
     * @param from the envelope sender
     * @param recipients the envelope recipients
     * @param rawMessage the raw message
     * @throws ProviderException when the submission fails
     */
    public void send(String from, List<String> recipients, byte[] rawMessage) {
        command("MAIL FROM:<" + from + ">", 250);
        for (String recipient : recipients) {
            command("RCPT TO:<" + recipient + ">", 250);
        }
        command("DATA", 354);
        try {
            out.write(dotStuff(rawMessage));
            out.write("\r\n.\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            throw ProviderException.network("Lost the connection to the Microsoft SMTP server", e);
        }
        String response = readLine();
        if (response == null || !response.startsWith("250")) {
            throw MicrosoftErrorMapper.classify("Microsoft rejected the message: " + response,
                    ErrorCode.SEND_FAILED);
        }
    }

    /**
     * Sends the QUIT command and closes the connection.
     */
    @Override
    public void close() {
        try {
            if (out != null) {
                command("QUIT", 221);
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

    private void command(String line, int expectedCode) {
        String response = commandRaw(line);
        if (!response.startsWith(String.valueOf(expectedCode))) {
            throw MicrosoftErrorMapper.classify("The Microsoft SMTP server answered '" + response
                    + "' to '" + line + "'", ErrorCode.PROTOCOL_ERROR);
        }
    }

    private String commandRaw(String line) {
        try {
            out.write(line.getBytes(StandardCharsets.UTF_8));
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            throw ProviderException.network("Lost the connection to the Microsoft SMTP server", e);
        }
        List<String> lines = new ArrayList<>();
        String response = readLine();
        while (response != null && response.length() >= 4
                && response.charAt(3) == '-' && Character.isDigit(response.charAt(0))) {
            lines.add(response);
            response = readLine();
        }
        if (response == null) {
            throw ProviderException.network("The Microsoft SMTP server closed the connection", null);
        }
        lines.add(response);
        return response;
    }

    private String readLine() {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int value;
            boolean any = false;
            while ((value = in.read()) != -1) {
                any = true;
                if (value == '\n') {
                    break;
                }
                if (value != '\r') {
                    buffer.write(value);
                }
            }
            if (!any) {
                return null;
            }
            return buffer.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw ProviderException.network("Lost the connection to the Microsoft SMTP server", e);
        }
    }

    private static byte[] dotStuff(byte[] message) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(message.length + 16);
        boolean atLineStart = true;
        for (byte b : message) {
            if (atLineStart && b == '.') {
                out.write('.');
            }
            out.write(b);
            atLineStart = b == '\n';
        }
        return out.toByteArray();
    }
}
