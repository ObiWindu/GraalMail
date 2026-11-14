/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.storage;

import com.graalmail.core.domain.Account;
import com.graalmail.core.domain.LocalDraft;
import com.graalmail.core.domain.Folder;
import com.graalmail.core.domain.Message;
import com.graalmail.core.domain.MessageListPage;
import com.graalmail.core.domain.SearchQuery;
import java.util.List;
import java.util.Optional;

public interface MailStore {

    void initialize();

    /** Lists message filters, lowest priority first. */
    default java.util.List<com.graalmail.core.filter.MessageFilter> listFilters() {
        return java.util.List.of();
    }

    /** Saves a message filter (insert or replace). */
    default void saveFilter(com.graalmail.core.filter.MessageFilter filter) {
    }

    /** Deletes a message filter. */
    default void deleteFilter(String filterId) {
    }

    /** Lists compose templates. */
    default java.util.List<com.graalmail.core.filter.MessageTemplate> listTemplates() {
        return java.util.List.of();
    }

    /** Saves a compose template (insert or replace). */
    default void saveTemplate(com.graalmail.core.filter.MessageTemplate template) {
    }

    /** Deletes a compose template. */
    default void deleteTemplate(String templateId) {
    }

    /** Lists snoozed messages. */
    default java.util.List<com.graalmail.core.filter.SnoozedMessage> listSnoozed() {
        return java.util.List.of();
    }

    /** Snoozes a message until {@code wakeAt}. */
    default void snoozeMessage(String messageId, String accountId, java.time.Instant wakeAt) {
    }

    /** Removes a snooze entry (message is back in the inbox). */
    default void unsnoozeMessage(String messageId) {
    }

    List<Account> listAccounts();

    Optional<Account> account(String accountId);

    void saveAccount(Account account);

    void deleteAccount(String accountId);

    List<Folder> listFolders(String accountId);

    Optional<Folder> folder(String accountId, String folderId);

    void saveFolders(String accountId, List<Folder> folders);

    MessageListPage listMessages(String accountId, String folderId, int limit, int offset);

    Optional<Message> message(String messageId);

    void saveMessage(Message message);

    void updateFlags(String messageId, boolean unread, boolean flagged);

    void moveMessage(String messageId, String folderId);

    void deleteMessage(String messageId);

    List<Message> search(SearchQuery query, int limit);

    void enqueue(PendingAction action);

    List<PendingAction> pendingActions(String accountId);

    void updatePendingAction(PendingAction action);

    List<Contact> listContacts();

    void saveContact(Contact contact);

    void deleteContact(String contactId);

    String setting(String key, String fallback);

    void putSetting(String key, String value);

    /** Removes cached message bodies and headers for an account. */
    void clearMessageCache(String accountId);

    /** Removes cached message bodies and headers for a specific folder of an account. */
    void clearMessageCache(String accountId, String folderId);

    /** Saves a local draft for autosave/recovery. */
    void saveLocalDraft(LocalDraft draft);

    /** Gets the local draft for an account. */
    Optional<LocalDraft> getLocalDraft(String accountId);

    /** Deletes a local draft. */
    void deleteLocalDraft(String draftId);

    /** Lists all recovered drafts. */
    List<LocalDraft> listRecoveredDrafts();

    void close();
}
