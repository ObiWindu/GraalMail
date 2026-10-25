/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.imap;

import com.graalmail.core.domain.Address;
import com.graalmail.core.domain.Attachment;
import com.graalmail.core.domain.Message;
import com.graalmail.core.domain.MessageDraft;
import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * Converts between RFC 822 messages and the provider neutral message model.
 *
 * <p>The parser is intentionally tolerant: a malformed part is skipped instead
 * of discarding the whole message.</p>
 */
public final class MailMime {

    private MailMime() {
    }

    /**
     * Parses a raw message into the neutral model.
     *
     * @param accountId the account the message belongs to
     * @param folderId the folder the message lives in
     * @param messageId the provider specific message id
     * @param raw the raw RFC 822 message
     * @param attachmentPrefix prefix used for the generated attachment ids
     * @return the parsed message
     */
    public static Message parse(String accountId, String folderId, String messageId, byte[] raw,
                                 String attachmentPrefix) {
        Message.Builder builder = Message.builder(messageId)
                .accountId(accountId)
                .folderId(folderId)
                .size(raw == null ? 0 : raw.length);
        try {
            MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()),
                    new ByteArrayInputStream(raw == null ? new byte[0] : raw));
            builder.subject(nullToEmpty(mime.getSubject()));
            builder.from(toAddress(mime.getFrom()));
            builder.to(toAddresses(mime.getRecipients(RecipientType.TO)));
            builder.cc(toAddresses(mime.getRecipients(RecipientType.CC)));
            builder.bcc(toAddresses(mime.getRecipients(RecipientType.BCC)));
            builder.replyTo(toAddresses(mime.getReplyTo()));
            Date sent = mime.getSentDate();
            if (sent != null) {
                builder.date(sent.toInstant());
            }
            builder.internetMessageId(firstHeader(mime, "Message-ID"));
            builder.headers(headers(mime));
            List<Attachment> attachments = new ArrayList<>();
            StringBuilder plain = new StringBuilder();
            StringBuilder html = new StringBuilder();
            collect(mime, plain, html, attachments, new ArrayList<>(), attachmentPrefix);
            builder.bodyText(plain.toString());
            builder.bodyHtml(html.toString());
            builder.attachments(attachments);
            builder.attachment(!attachments.isEmpty());
        } catch (MessagingException | IOException | RuntimeException e) {
            throw new ProviderException(ErrorCode.MALFORMED_RESPONSE,
                    "The message could not be parsed.", false, e);
        }
        return builder.build();
    }

    /**
     * Returns the leaf body parts of a raw message in the order used by the
     * attachment identifiers.
     *
     * @param raw the raw RFC 822 message
     * @return the leaf body parts
     * @throws MessagingException when the message cannot be parsed
     * @throws IOException when the message cannot be read
     */
    public static List<Part> bodyParts(byte[] raw) throws MessagingException, IOException {
        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(raw == null ? new byte[0] : raw));
        List<Part> parts = new ArrayList<>();
        collect(mime, new StringBuilder(), new StringBuilder(), new ArrayList<>(), parts, "");
        return parts;
    }

    /**
     * Builds a raw RFC 822 message from a draft.
     *
     * @param draft the draft to send
     * @param from the envelope sender
     * @return the raw message
     */
    public static byte[] build(MessageDraft draft, String from) {
        try {
            MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()));
            mime.setFrom(new InternetAddress(from));
            for (Address recipient : draft.to()) {
                mime.addRecipient(RecipientType.TO, new InternetAddress(recipient.email()));
            }
            for (Address recipient : draft.cc()) {
                mime.addRecipient(RecipientType.CC, new InternetAddress(recipient.email()));
            }
            for (Address recipient : draft.bcc()) {
                mime.addRecipient(RecipientType.BCC, new InternetAddress(recipient.email()));
            }
            mime.setSubject(draft.subject() == null ? "" : draft.subject(), "UTF-8");
            String html = draft.bodyHtml();
            String text = draft.bodyText() == null ? "" : draft.bodyText();
            if (html != null && !html.isBlank()) {
                MimeMultipart multipart = new MimeMultipart("alternative");
                MimeBodyPart plainPart = new MimeBodyPart();
                plainPart.setText(text, "UTF-8");
                multipart.addBodyPart(plainPart);
                MimeBodyPart htmlPart = new MimeBodyPart();
                htmlPart.setContent(html, "text/html; charset=UTF-8");
                multipart.addBodyPart(htmlPart);
                mime.setContent(multipart);
            } else {
                mime.setText(text, "UTF-8");
            }
            mime.saveChanges();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            mime.writeTo(out);
            return out.toByteArray();
        } catch (MessagingException | IOException e) {
            throw new ProviderException(ErrorCode.SEND_FAILED,
                    "The message could not be prepared for sending.", false, e);
        }
    }

    /**
     * Reads the content of a part as bytes.
     *
     * @param part the part to read
     * @return the decoded content
     * @throws IOException when the part cannot be read
     * @throws MessagingException when the part cannot be inspected
     */
    public static byte[] readContent(Part part) throws IOException, MessagingException {
        Object content = part.getContent();
        if (content instanceof byte[] bytes) {
            return bytes;
        }
        if (content instanceof InputStream in) {
            return in.readAllBytes();
        }
        if (content instanceof String text) {
            return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        return new byte[0];
    }

    private static void collect(Part part, StringBuilder plain, StringBuilder html,
                                List<Attachment> attachments, List<Part> parts,
                                String attachmentPrefix)
            throws MessagingException, IOException {
        Object content;
        try {
            content = part.getContent();
        } catch (IOException | RuntimeException e) {
            return;
        }
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                collect(multipart.getBodyPart(i), plain, html, attachments, parts, attachmentPrefix);
            }
            return;
        }
        int index = parts.size();
        parts.add(part);
        String disposition = part.getDisposition();
        String fileName = part.getFileName();
        boolean isAttachment = fileName != null
                || (disposition != null && disposition.equalsIgnoreCase(Part.ATTACHMENT))
                || (content instanceof byte[] && !isTextType(part.getContentType()));
        if (isAttachment) {
            String mimeType = part.getContentType();
            long size = content instanceof byte[] bytes ? bytes.length : 0;
            attachments.add(new Attachment(attachmentPrefix + index,
                    fileName == null ? "attachment" : fileName, mimeType, size, false, ""));
            return;
        }
        String type = part.getContentType();
        String value = content instanceof String text ? text : "";
        if (type != null && type.toLowerCase(java.util.Locale.ROOT).startsWith("text/html")) {
            html.append(value);
        } else if (value != null && !value.isEmpty()) {
            plain.append(value);
        }
    }

    private static boolean isTextType(String contentType) {
        return contentType != null
                && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("text/");
    }

    private static List<Message.Header> headers(MimeMessage mime) {
        List<Message.Header> headers = new ArrayList<>();
        try {
            java.util.Enumeration<jakarta.mail.Header> all = mime.getAllHeaders();
            while (all.hasMoreElements()) {
                jakarta.mail.Header header = all.nextElement();
                headers.add(new Message.Header(header.getName(), header.getValue()));
            }
        } catch (MessagingException | RuntimeException e) {
            // headers are optional, a failure must not break the message
        }
        return headers;
    }

    private static String firstHeader(MimeMessage mime, String name) {
        try {
            String[] values = mime.getHeader(name);
            if (values == null || values.length == 0) {
                return "";
            }
            return nullToEmpty(values[0]);
        } catch (MessagingException | RuntimeException e) {
            return "";
        }
    }

    private static Address toAddress(jakarta.mail.Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return new Address("", "");
        }
        jakarta.mail.Address raw = addresses[0];
        if (!(raw instanceof InternetAddress address)) {
            return new Address("", raw == null ? "" : raw.toString());
        }
        return new Address(address.getPersonal() == null ? "" : address.getPersonal(),
                address.getAddress());
    }

    private static List<Address> toAddresses(jakarta.mail.Address[] addresses) {
        List<Address> result = new ArrayList<>();
        if (addresses == null) {
            return result;
        }
        for (jakarta.mail.Address address : addresses) {
            result.add(toAddress(new jakarta.mail.Address[]{address}));
        }
        return result;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
