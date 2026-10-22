/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Declares what a provider can actually do. The UI uses this to hide actions
 * that would fail and to explain why an action is unavailable.
 */
public final class ProviderCapabilities {

    private final ProviderType providerType;
    private final Set<AuthMethod> authMethods;
    private final boolean serverSideSearch;
    private final boolean pushNotifications;
    private final boolean idleSupport;
    private final boolean draftSync;
    private final boolean labels;
    private final boolean flags;
    private final boolean attachments;
    private final boolean unifiedInbox;
    private final boolean offlineActions;
    private final boolean serverSideFlags;
    private final boolean sendCapable;
    private final Map<String, Boolean> platformSupport;
    private final List<String> restrictions;
    private final List<String> notes;
    private final String documentationUrl;

    private ProviderCapabilities(Builder b) {
        this.providerType = b.providerType;
        this.authMethods = Set.copyOf(b.authMethods);
        this.serverSideSearch = b.serverSideSearch;
        this.pushNotifications = b.pushNotifications;
        this.idleSupport = b.idleSupport;
        this.draftSync = b.draftSync;
        this.labels = b.labels;
        this.flags = b.flags;
        this.attachments = b.attachments;
        this.unifiedInbox = b.unifiedInbox;
        this.offlineActions = b.offlineActions;
        this.serverSideFlags = b.serverSideFlags;
        this.sendCapable = b.sendCapable;
        this.platformSupport = Map.copyOf(b.platformSupport);
        this.restrictions = List.copyOf(b.restrictions);
        this.notes = List.copyOf(b.notes);
        this.documentationUrl = b.documentationUrl;
    }

    public static Builder builder(ProviderType type) {
        return new Builder(type);
    }

    public ProviderType providerType() { return providerType; }
    public Set<AuthMethod> authMethods() { return authMethods; }
    public boolean supportsAuthMethod(AuthMethod method) { return authMethods.contains(method); }
    public boolean serverSideSearch() { return serverSideSearch; }
    public boolean pushNotifications() { return pushNotifications; }
    public boolean idleSupport() { return idleSupport; }
    public boolean draftSync() { return draftSync; }
    public boolean labels() { return labels; }
    public boolean flags() { return flags; }
    public boolean attachments() { return attachments; }
    public boolean unifiedInbox() { return unifiedInbox; }
    public boolean offlineActions() { return offlineActions; }
    public boolean serverSideFlags() { return serverSideFlags; }
    public boolean sendCapable() { return sendCapable; }
    public Map<String, Boolean> platformSupport() { return platformSupport; }

    /** True when the given platform key ("linux" or "android") is supported. */
    public boolean supportsPlatform(String platform) {
        return platformSupport.getOrDefault(platform, false);
    }

    public List<String> restrictions() { return restrictions; }
    public List<String> notes() { return notes; }
    public String documentationUrl() { return documentationUrl; }

    public static final class Builder {
        private final ProviderType providerType;
        private final Set<AuthMethod> authMethods = new LinkedHashSet<>();
        private boolean serverSideSearch;
        private boolean pushNotifications;
        private boolean idleSupport;
        private boolean draftSync;
        private boolean labels;
        private boolean flags;
        private boolean attachments;
        private boolean unifiedInbox;
        private boolean offlineActions;
        private boolean serverSideFlags;
        private boolean sendCapable;
        private final Map<String, Boolean> platformSupport = new LinkedHashMap<>();
        private final List<String> restrictions = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();
        private String documentationUrl = "";

        private Builder(ProviderType providerType) {
            this.providerType = providerType;
        }

        public Builder authMethods(AuthMethod... methods) {
            authMethods.addAll(List.of(methods));
            return this;
        }

        public Builder serverSideSearch(boolean v) { this.serverSideSearch = v; return this; }
        public Builder pushNotifications(boolean v) { this.pushNotifications = v; return this; }
        public Builder idleSupport(boolean v) { this.idleSupport = v; return this; }
        public Builder draftSync(boolean v) { this.draftSync = v; return this; }
        public Builder labels(boolean v) { this.labels = v; return this; }
        public Builder flags(boolean v) { this.flags = v; return this; }
        public Builder attachments(boolean v) { this.attachments = v; return this; }
        public Builder unifiedInbox(boolean v) { this.unifiedInbox = v; return this; }
        public Builder offlineActions(boolean v) { this.offlineActions = v; return this; }
        public Builder serverSideFlags(boolean v) { this.serverSideFlags = v; return this; }
        public Builder sendCapable(boolean v) { this.sendCapable = v; return this; }
        public Builder platform(String platform, boolean supported) {
            platformSupport.put(platform, supported);
            return this;
        }

        public Builder restriction(String v) { restrictions.add(v); return this; }
        public Builder note(String v) { notes.add(v); return this; }
        public Builder documentationUrl(String v) { this.documentationUrl = v; return this; }

        public ProviderCapabilities build() {
            return new ProviderCapabilities(this);
        }
    }
}
