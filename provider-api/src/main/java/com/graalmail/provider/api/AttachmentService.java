/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.api;

import com.graalmail.core.domain.Attachment;
import java.io.InputStream;
import java.nio.file.Path;

/** Attachment retrieval. Attachments are never executed or auto-opened. */
public interface AttachmentService {

    InputStream open(Attachment attachment);

    /** Downloads the attachment to the given path, creating parent directories. */
    void download(Attachment attachment, Path target);

    /** Maximum accepted attachment size in bytes, or -1 when unlimited. */
    default long maxAttachmentSize() {
        return -1;
    }
}
