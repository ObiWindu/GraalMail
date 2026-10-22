/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.storage;

import com.graalmail.core.domain.Account;
import com.graalmail.core.domain.Address;
import com.graalmail.core.domain.Message;
import com.graalmail.core.domain.SearchQuery;
import com.graalmail.core.recipient.RecipientSuggestion;
import com.graalmail.core.recipient.RecipientSuggester;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects the recipient candidates of the compose window from the local
 * address book, the addresses of the configured accounts and the recipients of
 * the messages that were seen recently.
 *
 * <p>Everything happens on the local database; no address is sent to a
 * provider while the suggestions are computed.</p>
 */
public final class RecipientSuggestionProvider {

    /** How many recent messages are scanned for recipients. */
    private static final int RECENT_MESSAGE_SCAN = 200;

    private final MailStore store;

    public RecipientSuggestionProvider(MailStore store) {
        this.store = store;
    }

    /**
     * Returns the ranked suggestions for a search term.
     *
     * @param query the search term, may be empty
     * @param limit the maximum number of suggestions
     * @return the ranked suggestions, best match first
     */
    public List<RecipientSuggestion> suggest(String query, int limit) {
        return RecipientSuggester.suggest(query, candidates(), limit);
    }

    /**
     * Returns every candidate without filtering.
     *
     * @return the candidates of the address book, the accounts and the history
     */
    public List<RecipientSuggestion> candidates() {
        List<RecipientSuggestion> candidates = new ArrayList<>();
        for (Contact contact : store.listContacts()) {
            candidates.add(RecipientSuggestion.contact(contact.name(), contact.email()));
        }
        for (Account account : store.listAccounts()) {
            candidates.add(RecipientSuggestion.account(account.displayName(), account.email()));
        }
        candidates.addAll(recentRecipients());
        return candidates;
    }

    /**
     * Returns the addresses that were used in the recent conversations.
     *
     * @return the recent recipients, most recent first
     */
    public List<RecipientSuggestion> recentRecipients() {
        List<RecipientSuggestion> recipients = new ArrayList<>();
        List<Message> messages = store.search(
                SearchQuery.builder().limit(RECENT_MESSAGE_SCAN).build(), RECENT_MESSAGE_SCAN);
        for (Message message : messages) {
            for (Address address : message.to()) {
                if (address.hasEmail()) {
                    recipients.add(RecipientSuggestion.recent(address.name(), address.email()));
                }
            }
            Address from = message.from();
            if (from.hasEmail()) {
                recipients.add(RecipientSuggestion.recent(from.name(), from.email()));
            }
        }
        return recipients;
    }
}
