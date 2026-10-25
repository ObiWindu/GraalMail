/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.testing.compatibility;

import com.graalmail.core.domain.Attachment;
import com.graalmail.core.domain.Folder;
import com.graalmail.core.domain.FolderType;
import com.graalmail.core.domain.Message;
import com.graalmail.core.domain.MessageDraft;
import com.graalmail.core.domain.MessageListPage;
import com.graalmail.core.domain.SearchQuery;
import com.graalmail.core.domain.SyncResult;
import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import com.graalmail.provider.api.AccountProvider;
import com.graalmail.provider.api.AccountSetup;
import com.graalmail.provider.api.AttachmentService;
import com.graalmail.provider.api.ConnectionTestResult;
import com.graalmail.provider.api.MailboxService;
import com.graalmail.provider.api.MessageService;
import com.graalmail.provider.api.MessageSubmissionService;
import com.graalmail.provider.api.ProviderCapabilities;
import com.graalmail.provider.api.ProviderSession;
import com.graalmail.provider.api.ProviderType;
import com.graalmail.provider.api.SearchService;
import com.graalmail.provider.api.SendResult;
import com.graalmail.provider.api.FolderSyncState;
import com.graalmail.provider.api.SynchronizationService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Enhanced in-memory provider with full protocol simulation for compatibility testing.
 * Supports UID-based synchronization, proper flag handling, and realistic error scenarios.
 */
public final class CompatibilityTestProvider implements AccountProvider {

    private final Map<String, Folder> folders = new LinkedHashMap<>();
    private final Map<String, Message> messages = new LinkedHashMap<>();
    private final Map<String, Long> messageUids = new LinkedHashMap<>();
    private long nextUid = 1;
    private final List<MessageDraft> sent = new ArrayList<>();
    private boolean failWithAuth = false;
    private boolean failWithNetwork = false;
    private boolean failWithTimeout = false;
    private long uidValidity = 1;
    private String highestModSeq = "1";

    public CompatibilityTestProvider() {
        folders.put("inbox", Folder.of("inbox", "Inbox", FolderType.INBOX));
        folders.put("sent", Folder.of("sent", "Sent", FolderType.SENT));
        folders.put("drafts", Folder.of("drafts", "Drafts", FolderType.DRAFTS));
        folders.put("trash", Folder.of("trash", "Trash", FolderType.TRASH));
        folders.put("spam", Folder.of("spam", "Spam", FolderType.SPAM));
    }

    public void addMessage(Message message) {
        messages.put(message.id(), message);
        messageUids.put(message.id(), nextUid++);
    }

    public void addMessages(List<Message> msgs) {
        for (Message m : msgs) {
            addMessage(m);
        }
    }

    public List<MessageDraft> sentMessages() {
        return sent;
    }

    public void failAuthentication(boolean fail) {
        this.failWithAuth = fail;
    }

    public void failNetwork(boolean fail) {
        this.failWithNetwork = fail;
    }

    public void failTimeout(boolean fail) {
        this.failWithTimeout = fail;
    }

    public void setUidValidity(long validity) {
        this.uidValidity = validity;
    }

    public long getUidValidity() {
        return uidValidity;
    }

    @Override
    public ProviderType type() {
        return ProviderType.GENERIC;
    }

    @Override
    public String displayName() {
        return "Compatibility Test Provider";
    }

    @Override
    public ProviderCapabilities capabilities() {
        return ProviderCapabilities.builder(ProviderType.GENERIC)
                .authMethods(com.graalmail.provider.api.AuthMethod.PASSWORD)
                .serverSideSearch(true)
                .idleSupport(false)
                .flags(true)
                .attachments(true)
                .draftSync(true)
                .offlineActions(true)
                .sendCapable(true)
                .platform("linux", true)
                .platform("android", true)
                .build();
    }

    @Override
    public ProviderSession openSession(AccountSetup setup) {
        if (failWithAuth) {
            throw ProviderException.authenticationFailed("Invalid credentials");
        }
        if (failWithNetwork) {
            throw ProviderException.network("Network unavailable", null);
        }
        return new CompatSession();
    }

    @Override
    public ConnectionTestResult testConnection(AccountSetup setup) {
        try (ProviderSession session = openSession(setup)) {
            return ConnectionTestResult.success("ok");
        } catch (ProviderException e) {
            return ConnectionTestResult.failure(e.userMessage(), e.code().name());
        }
    }

    private final class CompatSession implements ProviderSession {

        @Override
        public MailboxService mailboxes() {
            return new MailboxService() {
                @Override
                public List<Folder> listFolders() {
                    return new ArrayList<>(folders.values());
                }

                @Override
                public MessageListPage listMessages(String folderId, int page, int pageSize) {
                    if (failWithNetwork) throw ProviderException.network("Network error", null);
                    if (failWithTimeout) throw new ProviderException(com.graalmail.core.error.ErrorCode.TIMEOUT, "Timeout", true);

                    List<Message> result = new ArrayList<>();
                    for (Message message : messages.values()) {
                        if (message.folderId().equals(folderId)) {
                            result.add(message);
                        }
                    }
                    int from = Math.min(page * pageSize, result.size());
                    int to = Math.min(from + pageSize, result.size());
                    List<Message> slice = result.subList(from, to);
                    return new MessageListPage(slice, to < result.size(), result.size());
                }

                @Override
                public MessageListPage listMessagesByUidRange(String folderId, long startUid, long endUid) {
                    if (failWithNetwork) throw ProviderException.network("Network error", null);
                    if (failWithTimeout) throw new ProviderException(com.graalmail.core.error.ErrorCode.TIMEOUT, "Timeout", true);

                    List<Message> result = new ArrayList<>();
                    for (Map.Entry<String, Message> entry : messages.entrySet()) {
                        Message m = entry.getValue();
                        if (m.folderId().equals(folderId)) {
                            Long uid = messageUids.get(entry.getKey());
                            if (uid != null && uid >= startUid && uid <= endUid) {
                                result.add(m);
                            }
                        }
                    }
                    return new MessageListPage(result, false, result.size());
                }

                @Override
                public Folder refreshCounts(String folderId) {
                    Folder folder = folders.get(folderId);
                    if (folder == null) {
                        throw new ProviderException(com.graalmail.core.error.ErrorCode.MAILBOX_NOT_FOUND, "No such folder", false);
                    }
                    int total = 0;
                    int unread = 0;
                    for (Message message : messages.values()) {
                        if (message.folderId().equals(folderId)) {
                            total++;
                            if (message.unread()) unread++;
                        }
                    }
                    return folder.withCounts(total, unread);
                }
            };
        }

        @Override
        public MessageService messages() {
            return new MessageService() {
                @Override
                public Message getMessage(String messageId, boolean includeBody) {
                    if (failWithNetwork) throw ProviderException.network("Network error", null);
                    if (failWithTimeout) throw new ProviderException(com.graalmail.core.error.ErrorCode.TIMEOUT, "Timeout", true);

                    Message message = messages.get(messageId);
                    if (message == null) {
                        throw new ProviderException(com.graalmail.core.error.ErrorCode.MESSAGE_NOT_FOUND, "Message not found", false);
                    }
                    return message;
                }

                @Override
                public void setRead(String messageId, boolean read) {
                    if (failWithNetwork) throw ProviderException.network("Network error", null);
                    Message message = require(messageId);
                    messages.put(messageId, copy(message, m -> m.unread(!read)));
                }

                @Override
                public void setFlagged(String messageId, boolean flagged) {
                    if (failWithNetwork) throw ProviderException.network("Network error", null);
                    Message message = require(messageId);
                    messages.put(messageId, copy(message, m -> m.flagged(flagged)));
                }

                @Override
                public void move(String messageId, String targetFolderId) {
                    if (failWithNetwork) throw ProviderException.network("Network error", null);
                    Message message = require(messageId);
                    messages.put(messageId, copy(message, m -> m.folderId(targetFolderId)));
                }

                @Override
                public void delete(String messageId) {
                    if (failWithNetwork) throw ProviderException.network("Network error", null);
                    messages.remove(messageId);
                    messageUids.remove(messageId);
                }

                @Override
                public void markSpam(String messageId) {
                    if (failWithNetwork) throw ProviderException.network("Network error", null);
                    move(messageId, "spam");
                }

                @Override
                public void markNotSpam(String messageId) {
                    if (failWithNetwork) throw ProviderException.network("Network error", null);
                    move(messageId, "inbox");
                }

                @Override
                public String saveDraft(String draftJson) {
                    if (failWithNetwork) throw ProviderException.network("Network error", null);
                    return "draft-" + System.currentTimeMillis();
                }

                @Override
                public com.graalmail.provider.api.SendResult send(String payloadBase64) {
                    if (failWithNetwork) throw ProviderException.network("Network error", null);
                    if (failWithTimeout) throw new ProviderException(com.graalmail.core.error.ErrorCode.TIMEOUT, "Timeout", true);
                    return com.graalmail.provider.api.SendResult.accepted("fake-" + System.currentTimeMillis());
                }

                private Message require(String messageId) {
                    Message message = messages.get(messageId);
                    if (message == null) {
                        throw new ProviderException(com.graalmail.core.error.ErrorCode.MESSAGE_NOT_FOUND, "Message not found", false);
                    }
                    return message;
                }
            };
        }

        @Override
        public MessageSubmissionService submission() {
            return new MessageSubmissionService() {
                @Override
                public com.graalmail.provider.api.SendResult send(MessageDraft draft) {
                    if (failWithNetwork) throw ProviderException.network("Network error", null);
                    if (failWithTimeout) throw new ProviderException(com.graalmail.core.error.ErrorCode.TIMEOUT, "Timeout", true);
                    sent.add(draft);
                    return com.graalmail.provider.api.SendResult.accepted("fake-" + sent.size());
                }

                @Override
                public void saveDraft(MessageDraft draft) {
                    // no-op for the fake provider
                }
            };
        }

        @Override
        public AttachmentService attachments() {
            return new AttachmentService() {
                @Override
                public InputStream open(Attachment attachment) {
                    return new ByteArrayInputStream(new byte[0]);
                }

                @Override
                public void download(Attachment attachment, Path target) {
                    // no-op for the fake provider
                }
            };
        }

        @Override
        public SearchService search() {
            return new SearchService() {
                @Override
                public List<Message> search(SearchQuery query) {
                    if (failWithNetwork) throw ProviderException.network("Network error", null);
                    List<Message> result = new ArrayList<>();
                    for (Message message : messages.values()) {
                        if (query.text().isBlank()
                                || message.subject().toLowerCase().contains(query.text().toLowerCase())
                                || message.snippet().toLowerCase().contains(query.text().toLowerCase())) {
                            result.add(message);
                        }
                    }
                    return result;
                }

                @Override
                public boolean isServerSide() {
                    return true;
                }
            };
        }

        @Override
        public SynchronizationService synchronization() {
            return new SynchronizationService() {
                @Override
                public SyncResult synchronize(String folderId, FolderSyncState syncState) {
                    if (failWithNetwork) throw ProviderException.network("Network error", null);
                    if (failWithTimeout) throw new ProviderException(com.graalmail.core.error.ErrorCode.TIMEOUT, "Timeout", true);

                    // Simulate UID-based sync
                    List<Long> newUids = new ArrayList<>();
                    List<Long> updatedUids = new ArrayList<>();
                    List<Long> deletedUids = new ArrayList<>();

                    long lastUid = syncState.uidNext() > 0 ? syncState.uidNext() : 1;

                    for (Map.Entry<String, Message> entry : messages.entrySet()) {
                        Message m = entry.getValue();
                        if (!m.folderId().equals(folderId)) continue;

                        Long uid = messageUids.get(entry.getKey());
                        if (uid != null && uid >= lastUid) {
                            newUids.add(uid);
                        }
                    }

                    // Update UID tracking
                    long newUidNext = 0;
                    for (Message m : messages.values()) {
                        if (m.folderId().equals(folderId)) {
                            Long uid = messageUids.get(m.id());
                            if (uid != null && uid > newUidNext) newUidNext = uid;
                        }
                    }
                    newUidNext++;

                    return SyncResult.of(folderId, newUids, updatedUids, deletedUids,
                            uidValidity, newUidNext, highestModSeq, Collections.emptyList());
                }

                @Override
                public List<SyncResult> synchronizeAll() {
                    List<SyncResult> results = new ArrayList<>();
                    for (Folder folder : folders.values()) {
                        results.add(synchronize(folder.id(), FolderSyncState.neverSynced()));
                    }
                    return results;
                }

                @Override
                public boolean supportsIdle() {
                    return false;
                }
            };
        }

        @Override
        public void close() {
            // nothing to release
        }
    }

    private interface Mutation {
        Message.Builder apply(Message.Builder builder);
    }

    private static Message copy(Message message, Mutation mutation) {
        Message.Builder builder = Message.builder(message.id())
                .accountId(message.accountId())
                .folderId(message.folderId())
                .internetMessageId(message.internetMessageId())
                .subject(message.subject())
                .from(message.from())
                .to(message.to())
                .cc(message.cc())
                .bcc(message.bcc())
                .replyTo(message.replyTo())
                .date(message.date())
                .snippet(message.snippet())
                .unread(message.unread())
                .flagged(message.flagged())
                .draft(message.draft())
                .answered(message.answered())
                .attachment(message.hasAttachments())
                .size(message.size())
                .bodyText(message.bodyText())
                .bodyHtml(message.bodyHtml())
                .headers(message.headers())
                .attachments(message.attachments());
        return mutation.apply(builder).build();
    }
}
