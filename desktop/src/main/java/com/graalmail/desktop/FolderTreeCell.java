/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWandu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import com.graalmail.core.domain.Account;
import com.graalmail.core.domain.Folder;
import javafx.scene.control.TreeCell;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/** Renders accounts and folders in the desktop navigation tree. */
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
            setText(title);
            setAccessibleText(title + ", account " + account.email());
            setGraphic(null);
        } else if (value instanceof Folder folder) {
            getStyleClass().add("folder-row");
            Label name = new Label(folder.name());
            Region spacer = new Region();
            HBox row = new HBox(8, name, spacer);
            HBox.setHgrow(spacer, Priority.ALWAYS);
            if (folder.unreadCount() > 0) {
                Label count = new Label(Integer.toString(folder.unreadCount()));
                count.getStyleClass().add("folder-unread-count");
                row.getChildren().add(count);
            }
            setText(null);
            setGraphic(row);
            setAccessibleText(folder.name() + (folder.unreadCount() > 0
                    ? ", " + folder.unreadCount() + " unread" : ""));
        } else if (value == MainWindow.UNIFIED_INBOX) {
            getStyleClass().add("unified-inbox-row");
            setText("Unified Inbox");
            setAccessibleText("Unified Inbox, all accounts");
            setGraphic(null);
        } else {
            setText(String.valueOf(value));
            setGraphic(null);
        }
    }
}
