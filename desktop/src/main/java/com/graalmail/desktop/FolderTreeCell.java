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
import javafx.scene.control.MenuItem;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Renders Proton-style navigation rows: rounded pills with count badges. */
final class FolderTreeCell extends TreeCell<Object> {

    private final java.util.function.Consumer<Account> onRemoveAccount;
    private final java.util.function.ToIntFunction<Account> unreadCounts;
    private final java.util.function.BiConsumer<Account, com.graalmail.core.domain.Folder> onMarkAllRead;

    /** Creates cells without account actions. */
    FolderTreeCell() {
        this(account -> { }, account -> 0, (account, folder) -> { });
    }

    /**
     * Creates cells that offer account actions.
     *
     * @param onRemoveAccount called when "Remove account…" is chosen
     */
    FolderTreeCell(java.util.function.Consumer<Account> onRemoveAccount) {
        this(onRemoveAccount, account -> 0, (account, folder) -> { });
    }

    /**
     * Creates cells with account actions and per-account unread rollups.
     *
     * @param onRemoveAccount called when "Remove account…" is chosen
     * @param unreadCounts total unread messages per account, for the badge
     */
    FolderTreeCell(java.util.function.Consumer<Account> onRemoveAccount,
            java.util.function.ToIntFunction<Account> unreadCounts) {
        this(onRemoveAccount, unreadCounts, (account, folder) -> { });
    }

    /**
     * Creates cells with account actions, unread rollups and folder actions.
     *
     * @param onRemoveAccount called when "Remove account…" is chosen
     * @param unreadCounts total unread messages per account, for the badge
     * @param onMarkAllRead called when "Mark all as read" is chosen on a folder
     */
    FolderTreeCell(java.util.function.Consumer<Account> onRemoveAccount,
            java.util.function.ToIntFunction<Account> unreadCounts,
            java.util.function.BiConsumer<Account, com.graalmail.core.domain.Folder> onMarkAllRead) {
        this.onRemoveAccount = onRemoveAccount == null ? account -> { } : onRemoveAccount;
        this.unreadCounts = unreadCounts == null ? account -> 0 : unreadCounts;
        this.onMarkAllRead = onMarkAllRead == null ? (account, folder) -> { } : onMarkAllRead;
    }

    @Override
    protected void updateItem(Object value, boolean empty) {
        super.updateItem(value, empty);
        setContextMenu(null);
        getStyleClass().removeAll("account-row", "folder-row", "unified-inbox-row");
        if (empty || value == null) {
            setText(null);
            setGraphic(null);
        } else if (value instanceof Account account) {
            getStyleClass().add("account-row");
            String title = account.displayName().isBlank() ? account.email() : account.displayName();
            Label name = new Label(title);
            name.getStyleClass().add("account-name");
            name.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(name, Priority.ALWAYS);
            VBox text = new VBox(name);
            if (!account.displayName().isBlank() && !account.email().isBlank()) {
                Label email = new Label(account.email());
                email.getStyleClass().add("account-email");
                text.getChildren().add(email);
            }
            text.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(text, Priority.ALWAYS);
            javafx.scene.layout.StackPane avatar = ProtonAvatar.avatar(
                    account.displayName(), account.email(), 28 * UiScale.currentScale());
            HBox row = new HBox(avatar, text);
            int totalUnread = Math.max(0, unreadCounts.applyAsInt(account));
            if (totalUnread > 0) row.getChildren().add(badge(formatCount(totalUnread)));
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("folder-row-content");
            setText(null);
            setGraphic(row);
            setAccessibleText(title + ", account " + account.email() + (totalUnread > 0
                    ? ", " + totalUnread + " unread" : ""));
            MenuItem remove = new MenuItem("Remove account…");
            remove.setOnAction(event -> onRemoveAccount.accept(account));
            javafx.scene.control.ContextMenu menu =
                    new javafx.scene.control.ContextMenu(remove);
            setContextMenu(menu);
        } else if (value instanceof Folder folder) {
            getStyleClass().add("folder-row");
            setDisclosureNode(null);
            String type = iconFor(folder.type());
            Label count = folder.unreadCount() > 0
                    ? badge(formatCount(folder.unreadCount())) : null;
            setText(null);
            setGraphic(row(icon(type, type), folder.name(), "folder-name", count));
            setAccessibleText(folder.name() + (folder.unreadCount() > 0
                    ? ", " + folder.unreadCount() + " unread" : ""));
            MenuItem markRead = new MenuItem("Mark all as read");
            markRead.setDisable(folder.unreadCount() <= 0);
            markRead.setOnAction(event -> {
                TreeItem<Object> item = getTreeItem();
                Object parent = item == null || item.getParent() == null
                        ? null : item.getParent().getValue();
                if (parent instanceof Account account) {
                    onMarkAllRead.accept(account, folder);
                }
            });
            setContextMenu(new javafx.scene.control.ContextMenu(markRead));
        } else if (value == MainWindow.UNIFIED_INBOX) {
            getStyleClass().add("unified-inbox-row");
            setText(null);
            setGraphic(row(icon("inbox", "inbox"), "Unified Inbox", "folder-name", null));
            setAccessibleText("Unified Inbox, all accounts");
        } else {
            setText(null);
            setGraphic(null);
            setAccessibleText("No account configured. Use the Add account button below to get started.");
            setDisable(false);
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

    private static String formatCount(int count) {
        return count > 99 ? "99+" : Integer.toString(count);
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
