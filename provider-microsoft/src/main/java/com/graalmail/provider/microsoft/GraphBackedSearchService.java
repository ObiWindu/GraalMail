/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.microsoft;

import com.graalmail.core.domain.Message;
import com.graalmail.core.domain.SearchQuery;
import com.graalmail.provider.api.SearchService;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link SearchService} that uses the Microsoft Graph {@code $search}
 * operator, so the search runs on the server.
 */
public final class GraphBackedSearchService implements SearchService {

    private final MicrosoftGraphClient client;
    private final String accountId;

    /**
     * Creates the service.
     *
     * @param client the Graph client
     * @param accountId the account the messages belong to
     */
    public GraphBackedSearchService(MicrosoftGraphClient client, String accountId) {
        this.client = client;
        this.accountId = accountId;
    }

    @Override
    public List<Message> search(SearchQuery query) {
        List<Message> messages = new ArrayList<>();
        for (MicrosoftGraphClient.GraphMessage graphMessage
                : client.search(toGraphTerm(query), query.limit())) {
            messages.add(GraphMessages.toMessage(accountId, "", graphMessage));
        }
        return messages;
    }

    @Override
    public boolean isServerSide() {
        return true;
    }

    /**
     * Translates a provider neutral query into a Graph search expression.
     *
     * @param query the query to translate
     * @return the Graph search term
     */
    static String toGraphTerm(SearchQuery query) {
        if (!query.text().isBlank()) {
            return query.text();
        }
        if (!query.subject().isBlank()) {
            return "subject:" + query.subject();
        }
        if (!query.from().isBlank()) {
            return "from:" + query.from();
        }
        if (!query.to().isBlank()) {
            return "to:" + query.to();
        }
        return "*";
    }
}
