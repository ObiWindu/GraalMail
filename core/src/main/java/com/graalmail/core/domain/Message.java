/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** A message as stored locally or returned by a provider. */
public final class Message {
    private final String id;
    private final String accountId;
    private final String folderId;
    private final String internetMessageId;
    private final String subject;
    private final Address from;
    private final List<Address> to;
    private final List<Address> cc;
    private final List<Address> bcc;
    private final List<Address> replyTo;
    private final Instant date;
    private final String snippet;
    private final boolean unread;
    private final boolean flagged;
    private final boolean draft;
    private final boolean answered;
    private final boolean attachment;
    private final long size;
    private final String bodyText;
    private final String bodyHtml;
    private final List<Attachment> attachments;
    private final List<Header> headers;

    private Message(Builder b) {
        this.id = Objects.requireNonNull(b.id, "id");
        this.accountId = b.accountId == null ? "" : b.accountId;
        this.folderId = b.folderId == null ? "" : b.folderId;
        this.internetMessageId = b.internetMessageId == null ? "" : b.internetMessageId;
        this.subject = b.subject == null ? "" : b.subject;
        this.from = b.from == null ? new Address("", "") : b.from;
        this.to = List.copyOf(b.to);
        this.cc = List.copyOf(b.cc);
        this.bcc = List.copyOf(b.bcc);
        this.replyTo = List.copyOf(b.replyTo);
        this.date = b.date;
        this.snippet = b.snippet == null ? "" : b.snippet;
        this.unread = b.unread;
        this.flagged = b.flagged;
        this.draft = b.draft;
        this.answered = b.answered;
        this.attachment = b.attachment;
        this.size = b.size;
        this.bodyText = b.bodyText;
        this.bodyHtml = b.bodyHtml;
        this.attachments = List.copyOf(b.attachments);
        this.headers = List.copyOf(b.headers);
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public String id() { return id; }
    public String accountId() { return accountId; }
    public String folderId() { return folderId; }
    public String internetMessageId() { return internetMessageId; }
    public String subject() { return subject; }
    public Address from() { return from; }
    public List<Address> to() { return to; }
    public List<Address> cc() { return cc; }
    public List<Address> bcc() { return bcc; }
    public List<Address> replyTo() { return replyTo; }
    public Instant date() { return date; }
    public String snippet() { return snippet; }
    public boolean unread() { return unread; }
    public boolean flagged() { return flagged; }
    public boolean draft() { return draft; }
    public boolean answered() { return answered; }
    public boolean hasAttachments() { return attachment; }
    public long size() { return size; }
    public String bodyText() { return bodyText; }
    public String bodyHtml() { return bodyHtml; }
    public List<Attachment> attachments() { return attachments; }
    public List<Header> headers() { return headers; }

    public boolean hasBody() {
        return (bodyText != null && !bodyText.isEmpty()) || (bodyHtml != null && !bodyHtml.isEmpty());
    }

    /**
     * Creates a builder pre-filled with the values of this message.
     *
     * @return a builder holding a copy of this message state
     */
    public Builder toBuilder() {
        Builder builder = new Builder(id);
        builder.accountId = accountId;
        builder.folderId = folderId;
        builder.internetMessageId = internetMessageId;
        builder.subject = subject;
        builder.from = from;
        builder.to = new ArrayList<>(to);
        builder.cc = new ArrayList<>(cc);
        builder.bcc = new ArrayList<>(bcc);
        builder.replyTo = new ArrayList<>(replyTo);
        builder.date = date;
        builder.snippet = snippet;
        builder.unread = unread;
        builder.flagged = flagged;
        builder.draft = draft;
        builder.answered = answered;
        builder.attachment = attachment;
        builder.size = size;
        builder.bodyText = bodyText;
        builder.bodyHtml = bodyHtml;
        builder.attachments = new ArrayList<>(attachments);
        builder.headers = new ArrayList<>(headers);
        return builder;
    }

    public static final class Header {
        private final String name;
        private final String value;

        public Header(String name, String value) {
            this.name = name == null ? "" : name;
            this.value = value == null ? "" : value;
        }

        public String name() { return name; }
        public String value() { return value; }

        @Override
        public String toString() {
            return name + ": " + value;
        }
    }

    public static final class Builder {
        private final String id;
        private String accountId = "";
        private String folderId = "";
        private String internetMessageId = "";
        private String subject = "";
        private Address from = new Address("", "");
        private List<Address> to = new ArrayList<>();
        private List<Address> cc = new ArrayList<>();
        private List<Address> bcc = new ArrayList<>();
        private List<Address> replyTo = new ArrayList<>();
        private Instant date;
        private String snippet = "";
        private boolean unread;
        private boolean flagged;
        private boolean draft;
        private boolean answered;
        private boolean attachment;
        private long size;
        private String bodyText;
        private String bodyHtml;
        private List<Attachment> attachments = new ArrayList<>();
        private List<Header> headers = new ArrayList<>();

        private Builder(String id) {
            this.id = id;
        }

        public Builder accountId(String v) { this.accountId = v; return this; }
        public Builder folderId(String v) { this.folderId = v; return this; }
        public Builder internetMessageId(String v) { this.internetMessageId = v; return this; }
        public Builder subject(String v) { this.subject = v; return this; }
        public Builder from(Address v) { this.from = v; return this; }
        public Builder to(List<Address> v) { this.to = new ArrayList<>(v); return this; }
        public Builder cc(List<Address> v) { this.cc = new ArrayList<>(v); return this; }
        public Builder bcc(List<Address> v) { this.bcc = new ArrayList<>(v); return this; }
        public Builder replyTo(List<Address> v) { this.replyTo = new ArrayList<>(v); return this; }
        public Builder date(Instant v) { this.date = v; return this; }
        public Builder snippet(String v) { this.snippet = v; return this; }
        public Builder unread(boolean v) { this.unread = v; return this; }
        public Builder flagged(boolean v) { this.flagged = v; return this; }
        public Builder draft(boolean v) { this.draft = v; return this; }
        public Builder answered(boolean v) { this.answered = v; return this; }
        public Builder attachment(boolean v) { this.attachment = v; return this; }
        public Builder size(long v) { this.size = v; return this; }
        public Builder bodyText(String v) { this.bodyText = v; return this; }
        public Builder bodyHtml(String v) { this.bodyHtml = v; return this; }
        public Builder attachments(List<Attachment> v) { this.attachments = new ArrayList<>(v); return this; }
        public Builder headers(List<Header> v) { this.headers = new ArrayList<>(v); return this; }

        public Message build() {
            return new Message(this);
        }
    }
}
