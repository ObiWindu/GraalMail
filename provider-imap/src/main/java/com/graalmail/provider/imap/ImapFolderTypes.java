/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.imap;

import com.graalmail.core.domain.FolderType;
import java.util.List;
import java.util.Locale;

/**
 * Default folder role detection for generic IMAP servers.
 *
 * <p>The special use attributes {@code \Sent}, {@code \Drafts}, {@code \Trash},
 * {@code \Junk} and {@code \Archive} are honoured first. When a server does not
 * advertise them, well known mailbox names are matched as a fallback. Every
 * other mailbox becomes a {@link FolderType#CUSTOM} folder.</p>
 */
public final class ImapFolderTypes implements FolderTypeResolver {

    /** Shared stateless instance. */
    public static final ImapFolderTypes DEFAULT = new ImapFolderTypes();

    @Override
    public FolderType resolve(String folderName, List<String> attributes) {
        FolderType byAttribute = fromAttributes(attributes);
        if (byAttribute != null) {
            return byAttribute;
        }
        return fromName(folderName);
    }

    /**
     * Resolves the role from the special use attributes only.
     *
     * @param attributes the attributes reported by the server
     * @return the role or {@code null} when no attribute matched
     */
    public static FolderType fromAttributes(List<String> attributes) {
        if (attributes == null) {
            return null;
        }
        for (String attribute : attributes) {
            if (attribute == null) {
                continue;
            }
            switch (attribute.trim().toLowerCase(Locale.ROOT)) {
                case "\\sent" -> { return FolderType.SENT; }
                case "\\drafts" -> { return FolderType.DRAFTS; }
                case "\\trash" -> { return FolderType.TRASH; }
                case "\\junk" -> { return FolderType.SPAM; }
                case "\\spam" -> { return FolderType.SPAM; }
                case "\\archive" -> { return FolderType.ARCHIVE; }
                case "\\all" -> { return FolderType.ALL; }
                default -> { }
            }
        }
        return null;
    }

    /**
     * Resolves the role from the mailbox name.
     *
     * @param folderName the full name of the mailbox
     * @return the role, {@link FolderType#CUSTOM} for unknown mailboxes
     */
    public static FolderType fromName(String folderName) {
        if (folderName == null || folderName.isBlank()) {
            return FolderType.CUSTOM;
        }
        String name = folderName.trim().toLowerCase(Locale.ROOT);
        int slash = name.lastIndexOf('/');
        String leaf = slash >= 0 ? name.substring(slash + 1) : name;
        switch (leaf) {
            case "inbox" -> { return FolderType.INBOX; }
            case "sent", "sent items", "sent mail", "sent messages" -> { return FolderType.SENT; }
            case "drafts", "draft" -> { return FolderType.DRAFTS; }
            case "trash", "deleted items", "deleted messages", "bin" -> { return FolderType.TRASH; }
            case "junk", "spam", "junk e-mail", "junk mail" -> { return FolderType.SPAM; }
            case "archive", "archived items" -> { return FolderType.ARCHIVE; }
            case "all mail", "all messages" -> { return FolderType.ALL; }
            case "outbox", "sent items outbox" -> { return FolderType.OUTBOX; }
            default -> { }
        }
        if (leaf.contains("sent")) {
            return FolderType.SENT;
        }
        if (leaf.contains("draft")) {
            return FolderType.DRAFTS;
        }
        if (leaf.contains("trash") || leaf.contains("deleted items") || leaf.endsWith("bin")) {
            return FolderType.TRASH;
        }
        if (leaf.contains("junk") || leaf.contains("spam")) {
            return FolderType.SPAM;
        }
        if (leaf.contains("archiv")) {
            return FolderType.ARCHIVE;
        }
        if (leaf.contains("outbox")) {
            return FolderType.OUTBOX;
        }
        return FolderType.CUSTOM;
    }
}
