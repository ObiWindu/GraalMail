/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.storage;

import com.graalmail.core.domain.Account;
import com.graalmail.core.domain.Folder;
import com.graalmail.core.domain.Message;
import com.graalmail.core.domain.MessageListPage;
import com.graalmail.core.domain.SearchQuery;
import java.util.List;
import java.util.Optional;

/** Local persistence for accounts, folders, messages, contacts and pending actions. */
public interface MailStore {

    void initialize();

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

    void close();
}
