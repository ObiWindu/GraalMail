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
     * Addresses seen three times or more are marked frequent so they rank
     * above plain contacts.
     *
     * @return the recent recipients, most recent first
     */
    public List<RecipientSuggestion> recentRecipients() {
        List<RecipientSuggestion> recipients = new ArrayList<>();
        List<Message> messages = store.search(
                SearchQuery.builder().limit(RECENT_MESSAGE_SCAN).build(), RECENT_MESSAGE_SCAN);
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> names = new java.util.LinkedHashMap<>();
        java.util.List<String> order = new java.util.ArrayList<>();
        for (Message message : messages) {
            for (Address address : message.to()) {
                if (address.hasEmail()) {
                    noteAddress(counts, names, order, address);
                }
            }
            Address from = message.from();
            if (from.hasEmail()) {
                noteAddress(counts, names, order, from);
            }
        }
        for (String email : order) {
            String name = names.getOrDefault(email, "");
            int count = counts.getOrDefault(email, 1);
            recipients.add(count >= 3
                    ? RecipientSuggestion.frequent(name, email)
                    : RecipientSuggestion.recent(name, email));
        }
        return recipients;
    }

    private static void noteAddress(java.util.Map<String, Integer> counts,
                                    java.util.Map<String, String> names,
                                    java.util.List<String> order, Address address) {
        String key = address.email().toLowerCase(java.util.Locale.ROOT);
        counts.merge(key, 1, Integer::sum);
        if (!names.containsKey(key) && !address.name().isBlank()) {
            names.put(key, address.name());
        } else {
            names.putIfAbsent(key, "");
        }
        if (!order.contains(key)) {
            order.add(key);
        }
    }
}
