/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.microsoft;

import com.graalmail.core.domain.Attachment;
import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import com.graalmail.provider.api.AttachmentService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * An {@link AttachmentService} that downloads attachment content through the
 * Microsoft Graph API.
 */
public final class GraphBackedAttachmentService implements AttachmentService {

    private final MicrosoftGraphClient client;

    /**
     * Creates the service.
     *
     * @param client the Graph client
     */
    public GraphBackedAttachmentService(MicrosoftGraphClient client) {
        this.client = client;
    }

    @Override
    public InputStream open(Attachment attachment) {
        String[] parts = split(attachment.id());
        if (parts.length != 2) {
            throw new ProviderException(ErrorCode.ATTACHMENT_UNAVAILABLE,
                    "The attachment reference is not valid.", false);
        }
        byte[] content = client.downloadAttachment(parts[0], parts[1]);
        return new ByteArrayInputStream(content);
    }

    @Override
    public void download(Attachment attachment, Path target) {
        try (InputStream in = open(attachment)) {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            throw new ProviderException(ErrorCode.ATTACHMENT_UNAVAILABLE,
                    "The attachment could not be downloaded.", false, e);
        }
    }

    private static String[] split(String id) {
        if (id == null) {
            return new String[0];
        }
        int index = id.indexOf('|');
        if (index < 0) {
            return new String[0];
        }
        return new String[]{id.substring(0, index), id.substring(index + 1)};
    }
}
