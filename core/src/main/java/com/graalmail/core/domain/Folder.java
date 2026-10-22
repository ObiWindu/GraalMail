/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.domain;

import java.util.Objects;

/** A mailbox or label as exposed by a provider. */
public final class Folder {
    private final String id;
    private final String name;
    private final String parentPath;
    private final FolderType type;
    private final int totalCount;
    private final int unreadCount;
    private final boolean selectable;
    private final boolean supportsLabels;
    private final String providerSpecificType;

    public Folder(String id, String name, String parentPath, FolderType type,
                 int totalCount, int unreadCount, boolean selectable,
                 boolean supportsLabels, String providerSpecificType) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = name == null ? id : name;
        this.parentPath = parentPath == null ? "" : parentPath;
        this.type = type == null ? FolderType.CUSTOM : type;
        this.totalCount = totalCount;
        this.unreadCount = unreadCount;
        this.selectable = selectable;
        this.supportsLabels = supportsLabels;
        this.providerSpecificType = providerSpecificType == null ? "" : providerSpecificType;
    }

    public static Folder of(String id, String name, FolderType type) {
        return new Folder(id, name, "", type, 0, 0, true, false, "");
    }

    public String id() { return id; }
    public String name() { return name; }
    public String parentPath() { return parentPath; }
    public FolderType type() { return type; }
    public int totalCount() { return totalCount; }
    public int unreadCount() { return unreadCount; }
    public boolean selectable() { return selectable; }
    public boolean supportsLabels() { return supportsLabels; }
    public String providerSpecificType() { return providerSpecificType; }

    public Folder withCounts(int total, int unread) {
        return new Folder(id, name, parentPath, type, total, unread, selectable, supportsLabels,
                providerSpecificType);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Folder f && f.id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return name + " [" + type + "]";
    }
}
