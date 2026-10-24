/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWandu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import com.graalmail.core.domain.Account;
import com.graalmail.core.domain.Folder;
import javafx.scene.control.TreeCell;

/** Renders accounts and folders in the desktop navigation tree. */
final class FolderTreeCell extends TreeCell<Object> {

    @Override
    protected void updateItem(Object value, boolean empty) {
        super.updateItem(value, empty);
        if (empty || value == null) {
            setText(null);
        } else if (value instanceof Account account) {
            setText(account.displayName().isBlank() ? account.email() : account.displayName());
        } else if (value instanceof Folder folder) {
            setText(folder.name() + (folder.unreadCount() > 0 ? "    " + folder.unreadCount() : ""));
        } else if (value == MainWindow.UNIFIED_INBOX) {
            setText("✉  Unified Inbox");
        } else {
            setText(String.valueOf(value));
        }
    }
}
