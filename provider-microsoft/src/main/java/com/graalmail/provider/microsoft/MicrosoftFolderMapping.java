/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.microsoft;

import com.graalmail.core.domain.FolderType;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps the well known Outlook and Microsoft Graph mail folders onto the
 * provider-neutral {@link FolderType} values. Every other folder is a
 * {@link FolderType#CUSTOM} user folder.
 */
public final class MicrosoftFolderMapping {

    /** Display name to folder type for the well known Outlook folders. */
    private static final Map<String, FolderType> BY_DISPLAY_NAME = new LinkedHashMap<>();
    /** Display name to IMAP folder name for the well known Outlook folders. */
    private static final Map<String, String> IMAP_NAMES = new LinkedHashMap<>();
    /** Graph well known folder id to folder type. */
    private static final Map<String, FolderType> BY_GRAPH_ID = new LinkedHashMap<>();
    /** IMAP folder name to the Outlook display name. */
    private static final Map<String, String> DISPLAY_NAMES = new LinkedHashMap<>();

    static {
        put("Inbox", FolderType.INBOX, "INBOX", "inbox");
        put("Sent Items", FolderType.SENT, "Sent Items", "sentitems");
        put("Drafts", FolderType.DRAFTS, "Drafts", "drafts");
        put("Deleted Items", FolderType.TRASH, "Deleted Items", "deleteditems");
        put("Junk Email", FolderType.SPAM, "Junk Email", "junkemail");
        put("Archive", FolderType.ARCHIVE, "Archive", "archive");
    }

    private MicrosoftFolderMapping() {
    }

    private static void put(String displayName, FolderType type, String imapName, String graphId) {
        BY_DISPLAY_NAME.put(normalize(displayName), type);
        IMAP_NAMES.put(normalize(displayName), imapName);
        BY_GRAPH_ID.put(graphId, type);
        DISPLAY_NAMES.putIfAbsent(imapName.toLowerCase(Locale.ROOT), displayName);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Maps an Outlook display name to a folder type.
     *
     * @param displayName the folder display name
     * @return the mapped type, {@link FolderType#CUSTOM} for user folders
     */
    public static FolderType typeOf(String displayName) {
        FolderType type = BY_DISPLAY_NAME.get(normalize(displayName));
        return type == null ? FolderType.CUSTOM : type;
    }

    /**
     * Maps an Outlook display name to the matching IMAP folder name.
     *
     * @param displayName the folder display name
     * @return the IMAP folder name, or the display name itself for user folders
     */
    public static String imapNameOf(String displayName) {
        String name = IMAP_NAMES.get(normalize(displayName));
        return name == null ? (displayName == null ? "" : displayName) : name;
    }

    /**
     * Maps a Microsoft Graph well known folder id to a folder type.
     *
     * @param graphId the Graph folder id, for example {@code junkemail}
     * @return the mapped type, {@link FolderType#CUSTOM} for user folders
     */
    public static FolderType typeOfGraphId(String graphId) {
        if (graphId == null) {
            return FolderType.CUSTOM;
        }
        FolderType type = BY_GRAPH_ID.get(graphId.trim().toLowerCase(Locale.ROOT));
        return type == null ? FolderType.CUSTOM : type;
    }

    /**
     * Maps an IMAP folder name back to the Outlook display name.
     *
     * @param imapName the IMAP folder name
     * @return the Outlook display name, or the input for user folders
     */
    public static String displayNameOf(String imapName) {
        if (imapName == null) {
            return "";
        }
        String display = DISPLAY_NAMES.get(imapName.trim().toLowerCase(Locale.ROOT));
        return display == null ? imapName : display;
    }
}
