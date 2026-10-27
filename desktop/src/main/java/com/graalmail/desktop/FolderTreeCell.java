/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWandu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import com.graalmail.core.domain.Account;
import com.graalmail.core.domain.Folder;
import com.graalmail.core.domain.FolderType;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/** Renders account and folder rows using Thunderbird's mailbox icon set. */
final class FolderTreeCell extends TreeCell<Object> {

    @Override
    protected void updateItem(Object value, boolean empty) {
        super.updateItem(value, empty);
        getStyleClass().removeAll("account-row", "folder-row", "unified-inbox-row");
        if (empty || value == null) {
            setText(null);
            setGraphic(null);
        } else if (value instanceof Account account) {
            getStyleClass().add("account-row");
            String title = account.displayName().isBlank() ? account.email() : account.displayName();
            setText(null);
            setGraphic(row(icon("mail", "account"), title, "account-name", null));
            setAccessibleText(title + ", account " + account.email());
        } else if (value instanceof Folder folder) {
            getStyleClass().add("folder-row");
            String type = iconFor(folder.type());
            Label count = folder.unreadCount() > 0
                    ? badge(Integer.toString(folder.unreadCount())) : null;
            setText(null);
            setGraphic(row(icon(type, type), folder.name(), "folder-name", count));
            setAccessibleText(folder.name() + (folder.unreadCount() > 0
                    ? ", " + folder.unreadCount() + " unread" : ""));
        } else if (value == MainWindow.UNIFIED_INBOX) {
            getStyleClass().add("unified-inbox-row");
            setText(null);
            setGraphic(row(icon("inbox", "inbox"), "Unified Inbox", "folder-name", null));
            setAccessibleText("Unified Inbox, all accounts");
        } else {
            setText(String.valueOf(value));
            setGraphic(null);
        }
    }

    private static HBox row(Node icon, String title, String titleStyle, Label trailing) {
        Label name = new Label(title);
        name.getStyleClass().add(titleStyle);
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);
        HBox row = new HBox(icon, name);
        if (trailing != null) row.getChildren().add(trailing);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("folder-row-content");
        return row;
    }

    private static Label badge(String value) {
        Label count = new Label(value);
        count.getStyleClass().add("folder-unread-count");
        return count;
    }

    private static String iconFor(FolderType type) {
        return switch (type) {
            case INBOX, UNIFIED_INBOX -> "inbox";
            case DRAFTS -> "draft";
            case SENT -> "sent";
            case ARCHIVE -> "archive";
            case SPAM -> "spam";
            case TRASH -> "trash";
            case OUTBOX -> "outbox";
            default -> "folder";
        };
    }

    private static Group icon(String asset, String role) {
        return ThunderbirdIcons.icon(asset, "mail-folder-icon", role);
    }
}
