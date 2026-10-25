/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.api;

import com.graalmail.core.domain.Message;
import com.graalmail.core.domain.SearchQuery;
import java.util.List;

/** Search, server side where the provider supports it. */
public interface SearchService {

    List<Message> search(SearchQuery query);

    /** True when the results come from the provider rather than the local cache. */
    default boolean isServerSide() {
        return false;
    }
}
