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
import java.util.function.Predicate;

/** Renders KMail-style folder rows: icon, name, and an inline bold "(n)" unread count. */
final class FolderTreeCell extends TreeCell<Object> {

    /** Folder management actions offered in the context menus. */
    record FolderActions(
            java.util.function.BiConsumer<Account, com.graalmail.core.domain.Folder> onNewFolder,
            java.util.function.BiConsumer<Account, com.graalmail.core.domain.Folder> onRenameFolder,
            java.util.function.BiConsumer<Account, com.graalmail.core.domain.Folder> onDeleteFolder,
            java.util.function.BiConsumer<Account, com.graalmail.core.domain.Folder> onEmptyFolder,
            Predicate<Account> supported) {

        static FolderActions none() {
            return new FolderActions((a, f) -> { }, (a, f) -> { }, (a, f) -> { }, (a, f) -> { },
                    account -> false);
        }
    }

    private final java.util.function.Consumer<Account> onRemoveAccount;
    private final java.util.function.ToIntFunction<Account> unreadCounts;
    private final java.util.function.BiConsumer<Account, com.graalmail.core.domain.Folder> onMarkAllRead;
    private final FolderActions folderActions;

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
        this(onRemoveAccount, unreadCounts, onMarkAllRead, FolderActions.none());
    }

    /**
     * Creates cells with account actions, unread rollups, folder actions and
     * the folder management capability flag.
     */
    FolderTreeCell(java.util.function.Consumer<Account> onRemoveAccount,
            java.util.function.ToIntFunction<Account> unreadCounts,
            java.util.function.BiConsumer<Account, com.graalmail.core.domain.Folder> onMarkAllRead,
            FolderActions folderActions) {
        this.onRemoveAccount = onRemoveAccount == null ? account -> { } : onRemoveAccount;
        this.unreadCounts = unreadCounts == null ? account -> 0 : unreadCounts;
        this.onMarkAllRead = onMarkAllRead == null ? (account, folder) -> { } : onMarkAllRead;
        this.folderActions = folderActions == null ? FolderActions.none() : folderActions;
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
            HBox row = new HBox(6 * UiScale.currentScale(), avatar, text);
            int totalUnread = Math.max(0, unreadCounts.applyAsInt(account));
            if (totalUnread > 0) row.getChildren().add(unreadCount(formatCount(totalUnread)));
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("folder-row-content");
            setText(null);
            setGraphic(row);
            setAccessibleText(title + ", account " + account.email() + (totalUnread > 0
                    ? ", " + totalUnread + " unread" : ""));
            MenuItem newFolder = new MenuItem("New Folder…");
            boolean ops = folderActions.supported().test(account);
            newFolder.setDisable(!ops);
            newFolder.setOnAction(event -> folderActions.onNewFolder().accept(account, null));
            MenuItem remove = new MenuItem("Remove account…");
            remove.setOnAction(event -> onRemoveAccount.accept(account));
            javafx.scene.control.ContextMenu menu =
                    new javafx.scene.control.ContextMenu(newFolder, remove);
            setContextMenu(menu);
        } else if (value instanceof Folder folder) {
            getStyleClass().add("folder-row");
            setDisclosureNode(null);
            String type = iconFor(folder.type());
            Label count = folder.unreadCount() > 0
                    ? unreadCount(formatCount(folder.unreadCount())) : null;
            if (folder.unreadCount() > 0) {
                getStyleClass().add("folder-row-unread");
            }
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
            TreeItem<Object> item = getTreeItem();
            Object parent = item == null || item.getParent() == null
                    ? null : item.getParent().getValue();
            Account owner = parent instanceof Account account ? account : null;
            boolean ops = owner != null && folderActions.supported().test(owner);
            MenuItem newSubfolder = new MenuItem("New Subfolder…");
            newSubfolder.setDisable(!ops);
            newSubfolder.setOnAction(event -> {
                if (owner != null) folderActions.onNewFolder().accept(owner, folder);
            });
            MenuItem rename = new MenuItem("Rename…");
            rename.setDisable(!ops);
            rename.setOnAction(event -> {
                if (owner != null) folderActions.onRenameFolder().accept(owner, folder);
            });
            MenuItem emptyItem = new MenuItem("Empty Folder");
            emptyItem.setDisable(!ops);
            emptyItem.setOnAction(event -> {
                if (owner != null) folderActions.onEmptyFolder().accept(owner, folder);
            });
            MenuItem delete = new MenuItem("Delete Folder…");
            delete.setDisable(!ops);
            delete.setOnAction(event -> {
                if (owner != null) folderActions.onDeleteFolder().accept(owner, folder);
            });
            setContextMenu(new javafx.scene.control.ContextMenu(
                    markRead, new javafx.scene.control.SeparatorMenuItem(),
                    newSubfolder, rename, emptyItem, delete));
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

    /** KMail-style inline unread count: plain bold text "(n)" after the name. */
    private static Label unreadCount(String value) {
        Label count = new Label("(" + value + ")");
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
