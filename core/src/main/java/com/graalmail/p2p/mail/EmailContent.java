package com.graalmail.p2p.mail;

import java.util.List;

public record EmailContent(String subject, String bodyText, String bodyHtml, List<AttachmentRef> attachments) {
    public EmailContent {
        if (attachments == null) attachments = List.of();
    }
    public byte[] canonicalBytes() {
        StringBuilder sb = new StringBuilder();
        sb.append(subject == null ? "" : subject).append("\n");
        sb.append(bodyText == null ? "" : bodyText).append("\n");
        for (AttachmentRef a : attachments) sb.append(a.contentId()).append(":").append(a.size()).append("\n");
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
