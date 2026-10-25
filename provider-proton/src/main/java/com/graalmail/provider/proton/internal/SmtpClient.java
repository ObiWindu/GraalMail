/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Minimal SMTP client for the local Proton Mail Bridge. Supports implicit TLS
 * and STARTTLS, both with full certificate and hostname verification.
 */
public final class SmtpClient implements AutoCloseable {

    private final String host;
    private final int port;
    private final SSLContext sslContext;
    private final boolean startTls;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    private Socket socket;
    private BufferedReader reader;
    private OutputStream out;

    public SmtpClient(String host, int port, SSLContext sslContext, boolean startTls,
                      int connectTimeoutMillis, int readTimeoutMillis) {
        this.host = host;
        this.port = port;
        this.sslContext = sslContext;
        this.startTls = startTls;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    /** Opens the connection, negotiates TLS and authenticates. */
    public void connectAndLogin(String username, String password) throws IOException {
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
        plain.setSoTimeout(readTimeoutMillis);
        if (startTls) {
            reader = new BufferedReader(new InputStreamReader(plain.getInputStream(),
                    StandardCharsets.UTF_8));
            out = plain.getOutputStream();
            expect(readReply());
            command("EHLO graalmail");
            if (!readReply().contains("STARTTLS")) {
                plain.close();
                throw new IOException("Proton Mail Bridge did not offer STARTTLS");
            }
            command("STARTTLS");
            expect(readReply());
            socket = upgrade(plain);
        } else {
            socket = upgrade(plain);
        }
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                StandardCharsets.UTF_8));
        out = socket.getOutputStream();
        String capabilities = command("EHLO graalmail");
        if (!capabilities.contains("AUTH")) {
            throw new IOException("Proton Mail Bridge does not offer SMTP authentication");
        }
        command("AUTH LOGIN");
        expect(readReply());
        command(Base64.getEncoder().encodeToString(username.getBytes(StandardCharsets.UTF_8)));
        expect(readReply());
        command(Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8)));
        expect(readReply());
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

    /** Sends a message and returns the server reply. */
    public String send(byte[] message, String mailFrom, java.util.List<String> recipients)
            throws IOException {
        command("MAIL FROM:<" + mailFrom + ">");
        expect(readReply());
        for (String recipient : recipients) {
            command("RCPT TO:<" + recipient + ">");
            expect(readReply());
        }
        command("DATA");
        expect(readReply());
        out.write(dotStuff(message));
        out.write("\r\n.\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return readReply();
    }

    private byte[] dotStuff(byte[] message) {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(message.length + 16);
        boolean atLineStart = true;
        for (byte b : message) {
            if (atLineStart && b == '.') {
                buffer.write('.');
            }
            buffer.write(b);
            atLineStart = b == '\n';
        }
        return buffer.toByteArray();
    }

    private String command(String command) throws IOException {
        out.write((command + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        return "";
    }

    private String readReply() throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
            if (line.length() >= 4 && line.charAt(3) == '-') {
                continue;
            }
            break;
        }
        return sb.toString();
    }

    private void expect(String reply) throws IOException {
        if (reply.startsWith("2") || reply.startsWith("3")) {
            return;
        }
        throw new IOException("Proton Mail Bridge rejected the command: " + reply.trim());
    }

    @Override
    public void close() {
        try {
            if (out != null) {
                command("QUIT");
            }
        } catch (IOException ignored) {
            // the connection is closed anyway
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
