/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.domain;

import java.util.ArrayList;
import java.util.List;

/** A message being composed. */
public final class MessageDraft {
    private final String id;
    private final String accountId;
    private final List<Address> to;
    private final List<Address> cc;
    private final List<Address> bcc;
    private final String subject;
    private final String bodyText;
    private final String bodyHtml;
    private final List<Attachment> attachments;
    private final String inReplyTo;
    private final String references;
    private final String replyToFolderId;

    private MessageDraft(Builder b) {
        this.id = b.id == null ? "" : b.id;
        this.accountId = b.accountId == null ? "" : b.accountId;
        this.to = List.copyOf(b.to);
        this.cc = List.copyOf(b.cc);
        this.bcc = List.copyOf(b.bcc);
        this.subject = b.subject == null ? "" : b.subject;
        this.bodyText = b.bodyText == null ? "" : b.bodyText;
        this.bodyHtml = b.bodyHtml == null ? "" : b.bodyHtml;
        this.attachments = List.copyOf(b.attachments);
        this.inReplyTo = b.inReplyTo == null ? "" : b.inReplyTo;
        this.references = b.references == null ? "" : b.references;
        this.replyToFolderId = b.replyToFolderId == null ? "" : b.replyToFolderId;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String id() { return id; }
    public String accountId() { return accountId; }
    public List<Address> to() { return to; }
    public List<Address> cc() { return cc; }
    public List<Address> bcc() { return bcc; }
    public String subject() { return subject; }
    public String bodyText() { return bodyText; }
    public String bodyHtml() { return bodyHtml; }
    public List<Attachment> attachments() { return attachments; }
    public String inReplyTo() { return inReplyTo; }
    public String references() { return references; }
    public String replyToFolderId() { return replyToFolderId; }

    public boolean hasRecipients() {
        return !to.isEmpty() || !cc.isEmpty() || !bcc.isEmpty();
    }

    public static final class Builder {
        private String id;
        private String accountId = "";
        private List<Address> to = new ArrayList<>();
        private List<Address> cc = new ArrayList<>();
        private List<Address> bcc = new ArrayList<>();
        private String subject = "";
        private String bodyText = "";
        private String bodyHtml = "";
        private List<Attachment> attachments = new ArrayList<>();
        private String inReplyTo = "";
        private String references = "";
        private String replyToFolderId = "";

        public Builder id(String v) { this.id = v; return this; }
        public Builder accountId(String v) { this.accountId = v; return this; }
        public Builder to(List<Address> v) { this.to = new ArrayList<>(v); return this; }
        public Builder cc(List<Address> v) { this.cc = new ArrayList<>(v); return this; }
        public Builder bcc(List<Address> v) { this.bcc = new ArrayList<>(v); return this; }
        public Builder subject(String v) { this.subject = v; return this; }
        public Builder bodyText(String v) { this.bodyText = v; return this; }
        public Builder bodyHtml(String v) { this.bodyHtml = v; return this; }
        public Builder attachments(List<Attachment> v) { this.attachments = new ArrayList<>(v); return this; }
        public Builder inReplyTo(String v) { this.inReplyTo = v; return this; }
        public Builder references(String v) { this.references = v; return this; }
        public Builder replyToFolderId(String v) { this.replyToFolderId = v; return this; }

        public MessageDraft build() {
            return new MessageDraft(this);
        }
    }
}
