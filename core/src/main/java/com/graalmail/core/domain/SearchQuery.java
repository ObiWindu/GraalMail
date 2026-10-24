/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A provider-neutral search request. */
public final class SearchQuery {
    private final String text;
    private final String from;
    private final String to;
    private final String subject;
    private final List<String> folderIds;
    private final List<String> accountIds;
    private final boolean unreadOnly;
    private final boolean flaggedOnly;
    private final boolean withAttachmentsOnly;
    private final Instant since;
    private final Instant until;
    private final int limit;
    private final int offset;

    private SearchQuery(Builder b) {
        this.text = b.text == null ? "" : b.text;
        this.from = b.from == null ? "" : b.from;
        this.to = b.to == null ? "" : b.to;
        this.subject = b.subject == null ? "" : b.subject;
        this.folderIds = List.copyOf(b.folderIds);
        this.accountIds = List.copyOf(b.accountIds);
        this.unreadOnly = b.unreadOnly;
        this.flaggedOnly = b.flaggedOnly;
        this.withAttachmentsOnly = b.withAttachmentsOnly;
        this.since = b.since;
        this.until = b.until;
        this.limit = b.limit <= 0 ? 50 : b.limit;
        this.offset = Math.max(0, b.offset);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String text() { return text; }
    public String from() { return from; }
    public String to() { return to; }
    public String subject() { return subject; }
    public List<String> folderIds() { return folderIds; }
    public List<String> accountIds() { return accountIds; }
    public boolean unreadOnly() { return unreadOnly; }
    public boolean flaggedOnly() { return flaggedOnly; }
    public boolean withAttachmentsOnly() { return withAttachmentsOnly; }
    public Instant since() { return since; }
    public Instant until() { return until; }
    public int limit() { return limit; }
    public int offset() { return offset; }

    public boolean isEmpty() {
        return text.isBlank() && from.isBlank() && to.isBlank() && subject.isBlank()
                && folderIds.isEmpty() && accountIds.isEmpty() && !unreadOnly && !flaggedOnly && !withAttachmentsOnly
                && since == null && until == null;
    }

    public static final class Builder {
        private String text = "";
        private String from = "";
        private String to = "";
        private String subject = "";
        private List<String> folderIds = new ArrayList<>();
        private List<String> accountIds = new ArrayList<>();
        private boolean unreadOnly;
        private boolean flaggedOnly;
        private boolean withAttachmentsOnly;
        private Instant since;
        private Instant until;
        private int limit = 50;
        private int offset;

        public Builder text(String v) { this.text = v; return this; }
        public Builder from(String v) { this.from = v; return this; }
        public Builder to(String v) { this.to = v; return this; }
        public Builder subject(String v) { this.subject = v; return this; }
        public Builder folderIds(List<String> v) { this.folderIds = new ArrayList<>(v); return this; }
        public Builder accountIds(List<String> v) { this.accountIds = new ArrayList<>(v); return this; }
        public Builder unreadOnly(boolean v) { this.unreadOnly = v; return this; }
        public Builder flaggedOnly(boolean v) { this.flaggedOnly = v; return this; }
        public Builder withAttachmentsOnly(boolean v) { this.withAttachmentsOnly = v; return this; }
        public Builder since(Instant v) { this.since = v; return this; }
        public Builder until(Instant v) { this.until = v; return this; }
        public Builder limit(int v) { this.limit = v; return this; }
        public Builder offset(int v) { this.offset = v; return this; }

        public SearchQuery build() {
            return new SearchQuery(this);
        }
    }
}
