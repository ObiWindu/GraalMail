/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.filter;

import java.util.Objects;

/** One action a filter performs when it matches. */
public final class FilterAction {
    public enum Type {
        MARK_READ, MARK_FLAGGED, MOVE_TO_FOLDER, MARK_SPAM, DELETE
    }

    private final Type type;
    private final String targetFolderId;

    public FilterAction(Type type, String targetFolderId) {
        this.type = Objects.requireNonNull(type, "type");
        this.targetFolderId = targetFolderId == null ? "" : targetFolderId;
    }

    public static FilterAction markRead() {
        return new FilterAction(Type.MARK_READ, "");
    }

    public static FilterAction markFlagged() {
        return new FilterAction(Type.MARK_FLAGGED, "");
    }

    public static FilterAction moveTo(String folderId) {
        return new FilterAction(Type.MOVE_TO_FOLDER, folderId);
    }

    public static FilterAction markSpam() {
        return new FilterAction(Type.MARK_SPAM, "");
    }

    public static FilterAction delete() {
        return new FilterAction(Type.DELETE, "");
    }

    public Type type() { return type; }

    public String targetFolderId() { return targetFolderId; }
}
