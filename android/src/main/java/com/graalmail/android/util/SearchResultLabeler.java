/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.util;

/**
 * Builds the label that tells the user whether search results come from the
 * provider or from the local cache only. Free of Android types so that it can
 * be unit tested on a plain JVM.
 */
public final class SearchResultLabeler {

    /** How a set of search results was produced. */
    public enum Origin {
        /** The provider answered the query on the server. */
        SERVER,
        /** The query was answered from the local message cache. */
        LOCAL_CACHE,
        /** The provider does not support server-side search. */
        SERVER_UNSUPPORTED
    }

    private SearchResultLabeler() {
    }

    /**
     * @param origin      how the results were produced
     * @param providerName display name of the provider, may be blank
     */
    public static String label(Origin origin, String providerName) {
        String name = providerName == null ? "" : providerName.trim();
        switch (origin) {
            case SERVER:
                if (name.isEmpty()) {
                    return "Results from the mail server";
                }
                return "Results from " + name + " server";
            case SERVER_UNSUPPORTED:
                if (name.isEmpty()) {
                    return "This provider does not support server-side search, showing the local "
                            + "cache only";
                }
                return name + " does not support server-side search, showing the local cache only";
            case LOCAL_CACHE:
            default:
                return "Local cache only — only messages already downloaded are searched";
        }
    }

    /** True when the results are only the locally cached messages. */
    public static boolean isLocalOnly(Origin origin) {
        return origin != Origin.SERVER;
    }
}
