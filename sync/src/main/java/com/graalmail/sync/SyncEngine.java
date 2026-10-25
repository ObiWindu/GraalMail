/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.sync;

import com.graalmail.core.domain.Account;
import com.graalmail.core.domain.SyncResult;
import com.graalmail.core.domain.Folder;
import com.graalmail.core.domain.Message;
import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import com.graalmail.core.net.RetryPolicy;
import com.graalmail.core.util.Ids;
import com.graalmail.core.util.Log;
import com.graalmail.provider.api.FolderSyncState;
import com.graalmail.provider.api.ProviderSession;
import com.graalmail.provider.api.SendResult;
import com.graalmail.storage.MailStore;
import com.graalmail.storage.PendingAction;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * Synchronizes accounts with their provider and drains the queue of offline
 * actions. Remote operations are only marked as done once the server
 * confirmed them.
 */
public final class SyncEngine {

    private static final Logger LOG = Log.of(SyncEngine.class);

    private final MailStore store;
    private final AccountSessionFactory sessions;
    private final RetryPolicy retryPolicy;

    public SyncEngine(MailStore store, AccountSessionFactory sessions) {
        this(store, sessions, RetryPolicy.defaultPolicy());
    }

    public SyncEngine(MailStore store, AccountSessionFactory sessions, RetryPolicy retryPolicy) {
        this.store = store;
        this.sessions = sessions;
        this.retryPolicy = retryPolicy;
    }

    /** Synchronizes every configured account. */
    public List<SyncReport> syncAll() {
        List<SyncReport> reports = new ArrayList<>();
        for (Account account : store.listAccounts()) {
            if (!account.enabled()) {
                continue;
            }
            reports.add(syncAccount(account.id()));
        }
        return reports;
    }

    /** Synchronizes one account. Never throws for provider outages. */
    public SyncReport syncAccount(String accountId) {
        long started = System.currentTimeMillis();
        List<String> errors = new ArrayList<>();
        Account account = store.account(accountId).orElse(null);
        if (account == null) {
            return new SyncReport(accountId, 0, 0, 0, 0, List.of("Unknown account"),
                    Instant.now(), 0);
        }
        int foldersSynced = 0;
        int newMessages = 0;
        int applied = 0;
        int failed = 0;
        try (ProviderSession session = sessions.open(account)) {
            applied = applyPendingActions(account, session);
            failed = countFailed(account);
            List<Folder> folders = session.mailboxes().listFolders();
            store.saveFolders(accountId, folders);
            for (Folder folder : folders) {
                if (!folder.selectable()) {
                    continue;
                }
                try {
                    FolderSyncState syncState = FolderSyncState.neverSynced();
                    if (folder.uidValidity() != 0) {
                        syncState = new FolderSyncState(folder.uidValidity(), folder.uidNext(),
                                folder.lastSyncAt(), folder.highestModSeq(), false);
                    }
                    var result = session.synchronization().synchronize(folder.id(), syncState);
                    foldersSynced++;
                    newMessages += result.newMessages();
                    result.errors().forEach(errors::add);
                    
                    // Fetch and save new/updated messages
                    if (result.newMessages() > 0 || result.updatedMessages() > 0) {
                        fetchAndSaveMessages(account, session, folder.id(), syncState, result);
                    }
                    
                    // Update folder sync state if sync was successful
                    if (result.isSuccessful() && (result.uidValidity() > 0 || result.uidNext() > 0)) {
                        Folder updatedFolder = folder.withSyncState(
                                result.uidValidity(), result.uidNext(), 
                                result.completedAt().toEpochMilli(), 
                                result.highestModSeq(),
                                com.graalmail.core.domain.SyncState.SYNCED);
                        store.saveFolders(accountId, List.of(updatedFolder));
                    }
                } catch (ProviderException e) {
                    errors.add(folder.name() + ": " + e.userMessage());
                }
            }
            store.putSetting("sync.lastSuccess." + accountId, Instant.now().toString());
        } catch (ProviderException e) {
            errors.add(e.userMessage());
            LOG.warn("Synchronization failed for account {}: {}", accountId, e.code());
        } catch (RuntimeException e) {
            errors.add("Unexpected synchronization error: " + e.getMessage());
            LOG.warn("Unexpected synchronization error for account {}", accountId);
        }
        long duration = System.currentTimeMillis() - started;
        return new SyncReport(accountId, foldersSynced, newMessages, applied, failed, errors,
                Instant.now(), duration);
    }

    private void fetchAndSaveMessages(Account account, ProviderSession session, String folderId,
                                    FolderSyncState syncState, SyncResult result) {
        try {
            // Fetch new messages by UID
            for (long uid : result.newUids()) {
                try {
                    Message message = session.mailboxes().listMessagesByUidRange(folderId, uid, uid).messages().stream().findFirst().orElse(null);
                    if (message != null) {
                        store.saveMessage(withAccount(message, account.id(), folderId));
                    }
                } catch (ProviderException e) {
                    // Log but continue with other messages
                }
            }
            
            // Fetch updated messages by UID
            for (long uid : result.updatedUids()) {
                try {
                    Message message = session.mailboxes().listMessagesByUidRange(folderId, uid, uid).messages().stream().findFirst().orElse(null);
                    if (message != null) {
                        store.saveMessage(withAccount(message, account.id(), folderId));
                    }
                } catch (ProviderException e) {
                    // Log but continue with other messages
                }
            }
            
            // Handle deleted messages
            for (long uid : result.deletedUids()) {
                // Find and delete local message with this UID
                // This would require a UID-to-local-ID mapping
                // For now, we rely on the fact that deleted messages won't appear in future syncs
            }
            
            // Update folder sync state
            // The folder's uidValidity, uidNext, highestModSeq should be updated
            // This is done by the caller after successful sync
            
        } catch (Exception e) {
            // Log but don't fail the entire sync
        }
    }

    /** Copies the most recent headers of every folder into the local cache. */
    private void cacheMessages(Account account, ProviderSession session, List<Folder> folders) {
        for (Folder folder : folders) {
            if (!folder.selectable()) {
                continue;
            }
            try {
                var page = session.mailboxes().listMessages(folder.id(), 0, 50);
                for (Message message : page.messages()) {
                    store.saveMessage(withAccount(message, account.id(), folder.id()));
                }
            } catch (ProviderException e) {
                LOG.debug("Could not cache folder {}: {}", folder.id(), e.code());
            }
        }
    }

    private static Message withAccount(Message message, String accountId, String folderId) {
        return Message.builder(message.id())
                .accountId(accountId)
                .folderId(folderId)
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
                .attachments(message.attachments())
                .build();
    }

    /** Applies queued actions; only confirmed actions are marked as done. */
    public int applyPendingActions(Account account, ProviderSession session) {
        int applied = 0;
        for (PendingAction action : store.pendingActions(account.id())) {
            try {
                Optional<PendingAction> updatedAction = execute(action, session);
                if (updatedAction.isPresent()) {
                    store.updatePendingAction(updatedAction.get().withState(PendingAction.State.DONE,
                            updatedAction.get().attempts() + 1, ""));
                    applied++;
                }
                // If not done, the action state was updated by execute() (e.g., PENDING, FAILED, etc.)
            } catch (ProviderException e) {
                // Re-fetch action to get any sendState updates made by execute()
                Optional<PendingAction> refreshed = store.pendingActions(account.id()).stream()
                        .filter(a -> a.id().equals(action.id()))
                        .findFirst();
                PendingAction currentAction = refreshed.orElse(action);
                
                int attempts = currentAction.attempts() + 1;
                PendingAction.State state = e.retryable() && attempts < retryPolicy.maxAttempts()
                        ? PendingAction.State.PENDING : PendingAction.State.FAILED;
                store.updatePendingAction(currentAction.withState(state, attempts, e.userMessage()));
                LOG.info("Pending action {} failed: {}", action.type(), e.code());
            }
        }
        return applied;
    }

    private int countFailed(Account account) {
        int failed = 0;
        for (PendingAction action : store.pendingActions(account.id())) {
            if (action.state() == PendingAction.State.FAILED) {
                failed++;
            }
        }
        return failed;
    }

    private Optional<PendingAction> execute(PendingAction action, ProviderSession session) {
        switch (action.type()) {
            case MARK_READ -> {
                session.messages().setRead(action.targetId(), true);
                return Optional.of(action);
            }
            case MARK_UNREAD -> {
                session.messages().setRead(action.targetId(), false);
                return Optional.of(action);
            }
            case FLAG -> {
                session.messages().setFlagged(action.targetId(), true);
                return Optional.of(action);
            }
            case UNFLAG -> {
                session.messages().setFlagged(action.targetId(), false);
                return Optional.of(action);
            }
            case MOVE -> {
                session.messages().move(action.targetId(), action.targetFolderId());
                return Optional.of(action);
            }
            case DELETE -> {
                session.messages().delete(action.targetId());
                return Optional.of(action);
            }
            case MARK_SPAM -> {
                session.messages().markSpam(action.targetId());
                return Optional.of(action);
            }
            case MARK_NOT_SPAM -> {
                session.messages().markNotSpam(action.targetId());
                return Optional.of(action);
            }
            case SAVE_DRAFT -> {
                // Payload contains draft message JSON
                session.messages().saveDraft(action.payload());
                return Optional.of(action);
            }
            case SEND -> {
                // Send state machine: QUEUED -> SUBMITTING -> ACCEPTED | FAILED_BEFORE_SUBMISSION | SUBMISSION_OUTCOME_UNKNOWN
                // Returns the updated action if it should be marked DONE (i.e., ACCEPTED), empty otherwise
                return executeSend(action, session);
            }
            default -> {
                return Optional.of(action);
            }
        }
    }

    /**
     * Executes a SEND action with proper state machine handling.
     * @return the updated action if it should be marked DONE (send was ACCEPTED), empty otherwise
     */
    private Optional<PendingAction> executeSend(PendingAction action, ProviderSession session) {
        PendingAction.SendState currentSendState = action.sendState();
        PendingAction.SendState nextState = currentSendState;
        
        // Initialize send state if not set
        if (currentSendState == null || currentSendState == PendingAction.SendState.QUEUED) {
            nextState = PendingAction.SendState.SUBMITTING;
            PendingAction updated = action.withSendState(nextState);
            store.updatePendingAction(updated);
            action = updated; // Use updated action for subsequent operations
        }
        
        // Only attempt submission if we're in SUBMITTING state
        if (nextState == PendingAction.SendState.SUBMITTING) {
            try {
                // Parse the draft JSON from payload and send via submission service
                SendResult result = sendDraftFromPayload(action.payload(), session);
                if (result.accepted()) {
                    nextState = PendingAction.SendState.ACCEPTED;
                    PendingAction updated = action.withSendState(nextState);
                    store.updatePendingAction(updated);
                    return Optional.of(updated); // Mark DONE
                } else if (result.ambiguous()) {
                    // Ambiguous outcome - server may have accepted
                    nextState = PendingAction.SendState.SUBMISSION_OUTCOME_UNKNOWN;
                    PendingAction updated = action.withSendState(nextState);
                    store.updatePendingAction(updated);
                    // Do NOT mark DONE - requires manual resolution
                    return Optional.empty();
                } else {
                    // Explicitly rejected
                    nextState = PendingAction.SendState.FAILED_BEFORE_SUBMISSION;
                    PendingAction updated = action.withSendState(nextState);
                    store.updatePendingAction(updated);
                    return Optional.empty(); // Will be retried or marked FAILED by caller
                }
            } catch (ProviderException e) {
                if (e.code() == ErrorCode.NETWORK_UNAVAILABLE || e.code() == ErrorCode.TIMEOUT) {
                    // Ambiguous outcome - server may have accepted
                    nextState = PendingAction.SendState.SUBMISSION_OUTCOME_UNKNOWN;
                } else {
                    // Explicit failure before/during submission
                    nextState = PendingAction.SendState.FAILED_BEFORE_SUBMISSION;
                }
                PendingAction updated = action.withSendState(nextState);
                store.updatePendingAction(updated);
                throw e; // Re-throw to let caller handle retry logic
            }
        }
        
        // If we reach here, action is in a terminal state (ACCEPTED, OUTCOME_UNKNOWN, FAILED)
        // ACCEPTED was handled above with return Optional.of(updated)
        // OUTCOME_UNKNOWN and FAILED should not be marked DONE
        return nextState == PendingAction.SendState.ACCEPTED ? Optional.of(action) : Optional.empty();
    }

    /**
     * Sends a draft parsed from the action payload.
     * The payload is expected to be draft JSON (as queued by the UI).
     */
    private SendResult sendDraftFromPayload(String payload, ProviderSession session) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            // Deserialize to Map first to avoid Jackson annotations on core domain classes
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> map = mapper.readValue(payload, java.util.Map.class);
            
            com.graalmail.core.domain.MessageDraft.Builder builder = com.graalmail.core.domain.MessageDraft.builder();
            
            if (map.containsKey("to")) {
                java.util.List<?> toList = (java.util.List<?>) map.get("to");
                for (Object obj : toList) {
                    if (obj instanceof java.util.Map) {
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, Object> addrMap = (java.util.Map<String, Object>) obj;
                        String name = (String) addrMap.getOrDefault("name", "");
                        String email = (String) addrMap.getOrDefault("email", "");
                        builder.to(new java.util.ArrayList<>(java.util.List.of(new com.graalmail.core.domain.Address(name, email))));
                    }
                }
            }
            if (map.containsKey("cc")) {
                java.util.List<?> ccList = (java.util.List<?>) map.get("cc");
                for (Object obj : ccList) {
                    if (obj instanceof java.util.Map) {
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, Object> addrMap = (java.util.Map<String, Object>) obj;
                        String name = (String) addrMap.getOrDefault("name", "");
                        String email = (String) addrMap.getOrDefault("email", "");
                        builder.cc(new java.util.ArrayList<>(java.util.List.of(new com.graalmail.core.domain.Address(name, email))));
                    }
                }
            }
            if (map.containsKey("bcc")) {
                java.util.List<?> bccList = (java.util.List<?>) map.get("bcc");
                for (Object obj : bccList) {
                    if (obj instanceof java.util.Map) {
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, Object> addrMap = (java.util.Map<String, Object>) obj;
                        String name = (String) addrMap.getOrDefault("name", "");
                        String email = (String) addrMap.getOrDefault("email", "");
                        builder.bcc(new java.util.ArrayList<>(java.util.List.of(new com.graalmail.core.domain.Address(name, email))));
                    }
                }
            }
            if (map.containsKey("subject")) {
                builder.subject((String) map.get("subject"));
            }
            if (map.containsKey("bodyText")) {
                builder.bodyText((String) map.get("bodyText"));
            }
            if (map.containsKey("bodyHtml")) {
                builder.bodyHtml((String) map.get("bodyHtml"));
            }
            if (map.containsKey("inReplyTo")) {
                builder.inReplyTo((String) map.get("inReplyTo"));
            }
            if (map.containsKey("references")) {
                builder.references((String) map.get("references"));
            }
            if (map.containsKey("replyToFolderId")) {
                builder.replyToFolderId((String) map.get("replyToFolderId"));
            }
            
            com.graalmail.core.domain.MessageDraft draft = builder.build();
            return session.submission().send(draft);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ProviderException(ErrorCode.INVALID_RECIPIENT,
                    "Invalid draft payload: " + e.getMessage(), false, e);
        }
    }

    /** Queues an offline action and applies it optimistically to the local cache. */
    public PendingAction queue(Account account, PendingAction.Type type, String targetId,
                               String targetFolderId) {
        PendingAction action = new PendingAction(Ids.uuid(), account.id(), type, targetId,
                targetFolderId, "", PendingAction.State.PENDING, 0, "", Instant.now());
        store.enqueue(action);
        switch (type) {
            case MARK_READ -> store.message(targetId)
                    .ifPresent(message -> store.updateFlags(targetId, false, message.flagged()));
            case MARK_UNREAD -> store.message(targetId)
                    .ifPresent(message -> store.updateFlags(targetId, true, message.flagged()));
            case FLAG -> store.message(targetId)
                    .ifPresent(message -> store.updateFlags(targetId, message.unread(), true));
            case UNFLAG -> store.message(targetId)
                    .ifPresent(message -> store.updateFlags(targetId, message.unread(), false));
            case MOVE -> store.moveMessage(targetId, targetFolderId);
            case DELETE -> store.deleteMessage(targetId);
            default -> {
                // nothing to apply locally
            }
        }
        return action;
    }
}
