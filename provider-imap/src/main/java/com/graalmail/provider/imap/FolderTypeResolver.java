/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.imap;

import com.graalmail.core.domain.FolderType;
import java.util.List;

/**
 * Maps a native mailbox name to a provider neutral folder role.
 *
 * <p>Providers that expose different folder layouts, such as Gmail, implement
 * their own resolver instead of changing the IMAP provider.</p>
 */
public interface FolderTypeResolver {

    /**
     * Resolves the role of a mailbox.
     *
     * @param folderName the full name of the mailbox as reported by the server
     * @param attributes the special use attributes reported by the server, may be empty
     * @return the folder role, never {@code null}
     */
    FolderType resolve(String folderName, List<String> attributes);
}
