/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.domain;

import java.util.Objects;

/** Metadata for a message attachment. Content is fetched on demand. */
public final class Attachment {
    private final String id;
    private final String fileName;
    private final String mimeType;
    private final long size;
    private final boolean inline;
    private final String contentId;

    public Attachment(String id, String fileName, String mimeType, long size, boolean inline,
                      String contentId) {
        this.id = Objects.requireNonNull(id, "id");
        this.fileName = fileName == null ? "attachment" : fileName;
        this.mimeType = mimeType == null ? "application/octet-stream" : mimeType;
        this.size = size;
        this.inline = inline;
        this.contentId = contentId == null ? "" : contentId;
    }

    public String id() { return id; }
    public String fileName() { return fileName; }
    public String mimeType() { return mimeType; }
    public long size() { return size; }
    public boolean inline() { return inline; }
    public String contentId() { return contentId; }

    public boolean isImage() {
        return mimeType.toLowerCase().startsWith("image/");
    }

    @Override
    public String toString() {
        return fileName + " (" + mimeType + ", " + size + " bytes)";
    }
}
