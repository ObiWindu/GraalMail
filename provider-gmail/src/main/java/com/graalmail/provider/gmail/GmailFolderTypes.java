/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.gmail;

import com.graalmail.core.domain.FolderType;
import com.graalmail.provider.imap.FolderTypeResolver;
import java.util.List;
import java.util.Locale;

/**
 * Maps Gmail labels to the neutral folder roles.
 *
 * <p>Gmail exposes the mailbox system folders as labels with fixed names and
 * user labels as {@code [Label]} entries. The special use attributes are used
 * first when the server reports them.</p>
 */
public final class GmailFolderTypes implements FolderTypeResolver {

    /** Shared stateless instance. */
    public static final GmailFolderTypes DEFAULT = new GmailFolderTypes();

    /** Label name of the inbox. */
    public static final String LABEL_INBOX = "INBOX";
    /** Label name of the sent mail folder. */
    public static final String LABEL_SENT = "[Gmail]/Sent Mail";
    /** Label name of the drafts folder. */
    public static final String LABEL_DRAFT = "[Gmail]/Drafts";
    /** Label name of the trash folder. */
    public static final String LABEL_TRASH = "[Gmail]/Trash";
    /** Label name of the spam folder. */
    public static final String LABEL_SPAM = "[Gmail]/Spam";
    /** Label name of the archive. */
    public static final String LABEL_ARCHIVE = "[Gmail]/All Mail";
    /** Label name of the starred mail. */
    public static final String LABEL_STARRED = "[Gmail]/Starred";
    /** Label name of the important mail. */
    public static final String LABEL_IMPORTANT = "[Gmail]/Important";

    @Override
    public FolderType resolve(String folderName, List<String> attributes) {
        FolderType byAttribute = fromAttributes(attributes);
        if (byAttribute != null) {
            return byAttribute;
        }
        return fromLabel(folderName);
    }

    /**
     * Resolves the role of a Gmail label.
     *
     * @param label the label name as reported by IMAP
     * @return the role, {@link FolderType#CUSTOM} for user labels
     */
    public static FolderType fromLabel(String label) {
        if (label == null || label.isBlank()) {
            return FolderType.CUSTOM;
        }
        String normalized = label.trim();
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (upper.equals(LABEL_INBOX)) {
            return FolderType.INBOX;
        }
        switch (normalized.toLowerCase(Locale.ROOT)) {
            case "[gmail]/sent mail", "[gmail]/sent" -> { return FolderType.SENT; }
            case "[gmail]/drafts", "[gmail]/draft" -> { return FolderType.DRAFTS; }
            case "[gmail]/trash" -> { return FolderType.TRASH; }
            case "[gmail]/spam" -> { return FolderType.SPAM; }
            case "[gmail]/all mail" -> { return FolderType.ARCHIVE; }
            case "[gmail]/starred" -> { return FolderType.CUSTOM; }
            case "[gmail]/important" -> { return FolderType.CUSTOM; }
            default -> { }
        }
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            return FolderType.CUSTOM;
        }
        return FolderType.CUSTOM;
    }

    private static FolderType fromAttributes(List<String> attributes) {
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
}
