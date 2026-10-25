/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.microsoft;

import com.graalmail.core.domain.Address;
import com.graalmail.core.domain.Attachment;
import com.graalmail.core.domain.Message;
import com.graalmail.core.domain.MessageDraft;
import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import jakarta.mail.BodyPart;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * Converts between RFC 822 messages and the provider neutral message model.
 */
final class MicrosoftMime {

    private MicrosoftMime() {
    }

    /**
     * Parses a raw message.
     *
     * @param accountId the account the message belongs to
     * @param folderId the folder the message lives in
     * @param messageId the provider specific message id
     * @param raw the raw RFC 822 message
     * @param attachmentPrefix prefix for the generated attachment ids
     * @return the parsed message
     */
    static Message parse(String accountId, String folderId, String messageId, byte[] raw,
                         String attachmentPrefix) {
        Message.Builder builder = Message.builder(messageId)
                .accountId(accountId)
                .folderId(folderId)
                .size(raw.length);
        try {
            MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()),
                    new ByteArrayInputStream(raw));
            builder.subject(mime.getSubject() == null ? "" : mime.getSubject());
            builder.from(toAddress(mime.getFrom()));
            builder.to(toAddresses(mime.getRecipients(RecipientType.TO)));
            builder.cc(toAddresses(mime.getRecipients(RecipientType.CC)));
            builder.bcc(toAddresses(mime.getRecipients(RecipientType.BCC)));
            builder.replyTo(toAddresses(mime.getReplyTo()));
            Date sent = mime.getSentDate();
            if (sent != null) {
                builder.date(sent.toInstant());
            }
            String[] messageIds = mime.getHeader("Message-ID");
            if (messageIds != null && messageIds.length > 0) {
                builder.internetMessageId(messageIds[0]);
            }
            List<Attachment> attachments = new ArrayList<>();
            StringBuilder plain = new StringBuilder();
            StringBuilder html = new StringBuilder();
            collect(mime, plain, html, attachments, new ArrayList<>(), attachmentPrefix);
            builder.bodyText(plain.toString());
            builder.bodyHtml(html.toString());
            builder.attachments(attachments);
            builder.attachment(!attachments.isEmpty());
        } catch (MessagingException | IOException e) {
            throw new ProviderException(ErrorCode.MALFORMED_RESPONSE,
                    "The message from Microsoft could not be parsed.", false, e);
        }
        return builder.build();
    }

    /**
     * Flattens the body parts of a message in the order used by the
     * attachment identifiers.
     *
     * @param raw the raw RFC 822 message
     * @return the leaf body parts
     * @throws MessagingException when the message cannot be parsed
     * @throws IOException when the message cannot be read
     */
    static List<Part> bodyParts(byte[] raw) throws MessagingException, IOException {
        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(raw));
        List<Part> parts = new ArrayList<>();
        collect(mime, new StringBuilder(), new StringBuilder(), new ArrayList<>(), parts, "");
        return parts;
    }

    private static void collect(Part part, StringBuilder plain, StringBuilder html,
                                List<Attachment> attachments, List<Part> parts,
                                String attachmentPrefix)
            throws MessagingException, IOException {
        Object content = part.getContent();
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
        if (fileName != null || (disposition != null && disposition.equalsIgnoreCase(Part.ATTACHMENT))) {
            String mimeType = part.getContentType();
            long size = content instanceof byte[] bytes ? bytes.length : 0;
            attachments.add(new Attachment(attachmentPrefix + index,
                    fileName == null ? "attachment" : fileName, mimeType, size, false, ""));
            return;
        }
        String type = part.getContentType();
        String value = content instanceof String text ? text : "";
        if (type != null && type.toLowerCase().startsWith("text/html")) {
            html.append(value);
        } else {
            plain.append(value);
        }
    }

    /**
     * Builds a raw RFC 822 message from a draft.
     *
     * @param draft the draft to send
     * @param from the envelope sender
     * @return the raw message
     */
    static byte[] build(MessageDraft draft, String from) {
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
            mime.setSubject(draft.subject(), "UTF-8");
            if (!draft.bodyHtml().isBlank()) {
                MimeMultipart multipart = new MimeMultipart("alternative");
                MimeBodyPart text = new MimeBodyPart();
                text.setText(draft.bodyText(), "UTF-8");
                multipart.addBodyPart(text);
                MimeBodyPart html = new MimeBodyPart();
                html.setContent(draft.bodyHtml(), "text/html; charset=UTF-8");
                multipart.addBodyPart(html);
                mime.setContent(multipart);
            } else {
                mime.setText(draft.bodyText(), "UTF-8");
            }
            mime.saveChanges();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            mime.writeTo(out);
            return out.toByteArray();
        } catch (MessagingException | IOException e) {
            throw new ProviderException(ErrorCode.SEND_FAILED,
                    "The message could not be prepared for Microsoft.", false, e);
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
}
