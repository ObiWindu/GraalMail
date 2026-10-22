/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.sync;

import com.graalmail.core.domain.Account;
import com.graalmail.core.domain.Folder;
import com.graalmail.core.domain.Message;
import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import com.graalmail.core.net.RetryPolicy;
import com.graalmail.core.util.Ids;
import com.graalmail.core.util.Log;
import com.graalmail.provider.api.ProviderSession;
import com.graalmail.storage.MailStore;
import com.graalmail.storage.PendingAction;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
                    var result = session.synchronization().synchronize(folder.id());
                    foldersSynced++;
                    newMessages += result.newMessages();
                    result.errors().forEach(errors::add);
                } catch (ProviderException e) {
                    errors.add(folder.name() + ": " + e.userMessage());
                }
            }
            cacheMessages(account, session, folders);
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
                execute(action, session);
                store.updatePendingAction(action.withState(PendingAction.State.DONE,
                        action.attempts() + 1, ""));
                applied++;
            } catch (ProviderException e) {
                int attempts = action.attempts() + 1;
                PendingAction.State state = e.retryable() && attempts < retryPolicy.maxAttempts()
                        ? PendingAction.State.PENDING : PendingAction.State.FAILED;
                store.updatePendingAction(action.withState(state, attempts, e.userMessage()));
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

    private void execute(PendingAction action, ProviderSession session) {
        switch (action.type()) {
            case MARK_READ -> session.messages().setRead(action.targetId(), true);
            case MARK_UNREAD -> session.messages().setRead(action.targetId(), false);
            case FLAG -> session.messages().setFlagged(action.targetId(), true);
            case UNFLAG -> session.messages().setFlagged(action.targetId(), false);
            case MOVE -> session.messages().move(action.targetId(), action.targetFolderId());
            case DELETE -> session.messages().delete(action.targetId());
            case MARK_SPAM -> session.messages().markSpam(action.targetId());
            case MARK_NOT_SPAM -> session.messages().markNotSpam(action.targetId());
            case SAVE_DRAFT, SEND -> throw new ProviderException(ErrorCode.NOT_IMPLEMENTED,
                    "Queued drafts are sent from the compose window", false);
        }
    }

    /** Queues an offline action and applies it optimistically to the local cache. */
    public PendingAction queue(Account account, PendingAction.Type type, String targetId,
                               String targetFolderId) {
        PendingAction action = new PendingAction(Ids.uuid(), account.id(), type, targetId,
                targetFolderId, "", PendingAction.State.PENDING, 0, "", Instant.now());
        store.enqueue(action);
        switch (type) {
            case MARK_READ -> store.updateFlags(targetId, true, false);
            case MARK_UNREAD -> store.updateFlags(targetId, false, false);
            case MOVE -> store.moveMessage(targetId, targetFolderId);
            case DELETE -> store.deleteMessage(targetId);
            default -> {
                // nothing to apply locally
            }
        }
        return action;
    }
}
