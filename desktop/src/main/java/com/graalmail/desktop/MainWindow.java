/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import com.graalmail.core.domain.Account;
import com.graalmail.core.domain.Folder;
import com.graalmail.core.domain.Message;
import com.graalmail.core.domain.SearchQuery;
import com.graalmail.core.text.HtmlSanitizer;
import com.graalmail.core.util.Log;
import com.graalmail.storage.MailStore;
import com.graalmail.sync.SyncReport;
import java.util.List;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.slf4j.Logger;

/**
 * The main window of the desktop application.
 *
 * <p>The layout follows the classic three pane model: accounts and mailboxes
 * on the left, the message list in the middle and the reading pane on the
 * right.</p>
 */
public final class MainWindow {

    private static final Logger LOG = Log.of(MainWindow.class);

    private final DesktopContext context;
    private final DesktopSettings settings;
    private final Notifier notifier;
    private final ComposeWindow compose;

    private final TreeView<Object> folderTree = new TreeView<>();
    private final ObservableList<Message> messages = FXCollections.observableArrayList();
    private final ListView<Message> messageList = new ListView<>(messages);
    private final WebView reader = new WebView();
    private final TextField searchField = new TextField();
    private final Label status = new Label("Ready");
    private final SimpleObjectProperty<Account> selectedAccount = new SimpleObjectProperty<>();
    private final SimpleObjectProperty<Folder> selectedFolder = new SimpleObjectProperty<>();
    private final SimpleStringProperty searchText = new SimpleStringProperty("");
    private Stage owner;

    /**
     * Creates the window.
     *
     * @param context the application context
     */
    public MainWindow(DesktopContext context) {
        this.context = context;
        this.settings = new DesktopSettings(context.store());
        this.notifier = new Notifier();
        this.compose = new ComposeWindow(context);
    }

    /**
     * Builds the scene of the window.
     *
     * @return the scene
     */
    public Scene build() {
        return build(null);
    }

    /**
     * Builds the scene of the window.
     *
     * @param owner the stage of the window, may be {@code null}
     * @return the scene
     */
    public Scene build(Stage owner) {
        this.owner = owner;
        BorderPane root = new BorderPane();
        root.setTop(buildMenuBar());
        root.setCenter(buildCenter());
        root.setBottom(buildStatusBar());

        folderTree.getSelectionModel().selectedItemProperty().addListener((observable, old, item) ->
                onFolderSelected(item));
        messageList.getSelectionModel().selectedItemProperty().addListener((observable, old, item) ->
                onMessageSelected(item));
        searchField.textProperty().bindBidirectional(searchText);
        searchText.addListener((observable, old, value) -> runSearch(value));
        installShortcuts();
        root.setOnKeyPressed(event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.N) {
                composeNewMessage();
                event.consume();
            } else if (event.isControlDown() && event.getCode() == KeyCode.R) {
                replyToSelected();
                event.consume();
            }
        });

        refreshFolders();
        return new Scene(root, 1200, 780);
    }

    private MenuBar buildMenuBar() {
        Menu file = new Menu("_File");
        MenuItem addAccount = new MenuItem("_Add account");
        addAccount.setOnAction(event -> addAccount());
        MenuItem composeItem = new MenuItem("_New message");
        composeItem.setOnAction(event -> composeNewMessage());
        MenuItem replyItem = new MenuItem("_Reply");
        replyItem.setOnAction(event -> replyToSelected());
        MenuItem forwardItem = new MenuItem("_Forward");
        forwardItem.setOnAction(event -> forwardSelected());
        MenuItem sync = new MenuItem("_Synchronise now");
        sync.setOnAction(event -> syncNow());
        MenuItem exit = new MenuItem("E_xit");
        exit.setOnAction(event -> Platform.exit());
        file.getItems().addAll(addAccount, composeItem, replyItem, sync, exit);

        Menu tools = new Menu("_Tools");
        MenuItem settingsItem = new MenuItem("_Settings…");
        settingsItem.setOnAction(event -> showSettings());
        MenuItem diagnosticsItem = new MenuItem("_Diagnostics…");
        diagnosticsItem.setOnAction(event -> showDiagnostics());
        tools.getItems().addAll(settingsItem, diagnosticsItem);

        Menu help = new Menu("_Help");
        MenuItem about = new MenuItem("_About GraalMail");
        about.setOnAction(event -> showAbout());
        help.getItems().add(about);

        MenuBar bar = new MenuBar(file, tools, help);
        bar.setUseSystemMenuBar(true);
        return bar;
    }

    private SplitPane buildCenter() {
        folderTree.setShowRoot(true);
        folderTree.setCellFactory(view -> new FolderTreeCell());
        folderTree.setPrefWidth(260);

        messageList.setPrefWidth(420);
        messageList.setCellFactory(view -> new MessageListCell());

        VBox listPane = new VBox(6, searchField, messageList);
        VBox.setVgrow(messageList, Priority.ALWAYS);
        listPane.setPadding(new Insets(6));

        HBox readerPane = new HBox(reader);
        HBox.setHgrow(reader, Priority.ALWAYS);

        SplitPane split = new SplitPane(folderTree, listPane, readerPane);
        split.setDividerPositions(0.22, 0.55);
        return split;
    }

    private HBox buildStatusBar() {
        Button syncButton = new Button("Synchronise");
        syncButton.setOnAction(event -> syncNow());
        HBox box = new HBox(10, status, syncButton);
        box.setPadding(new Insets(6));
        box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return box;
    }

    private void refreshFolders() {
        TreeItem<Object> root = folderTree.getRoot();
        if (root == null) {
            root = new TreeItem<>("Accounts");
            folderTree.setRoot(root);
        }
        root.getChildren().setAll(buildTreeItems());
    }

    private List<TreeItem<Object>> buildTreeItems() {
        List<TreeItem<Object>> items = new java.util.ArrayList<>();
        for (Account account : context.store().listAccounts()) {
            TreeItem<Object> accountItem = new TreeItem<>(account);
            for (Folder folder : context.store().listFolders(account.id())) {
                accountItem.getChildren().add(new TreeItem<>(folder));
            }
            accountItem.setExpanded(true);
            items.add(accountItem);
        }
        if (items.isEmpty()) {
            TreeItem<Object> placeholder = new TreeItem<>("No account configured");
            placeholder.setExpanded(true);
            items.add(placeholder);
        }
        return items;
    }

    private void onFolderSelected(TreeItem<Object> item) {
        if (item == null) {
            return;
        }
        Object value = item.getValue();
        if (value instanceof Account account) {
            selectedAccount.set(account);
            selectedFolder.set(null);
            messages.clear();
            status.setText(account.email());
        } else if (value instanceof Folder folder) {
            selectedFolder.set(folder);
            Account account = selectedAccount.get();
            if (account != null) {
                loadMessages(account.id(), folder.id());
            }
        }
    }

    private void loadMessages(String accountId, String folderId) {
        List<Message> cached = context.store()
                .listMessages(accountId, folderId, settings.pageSize(), 0).messages();
        messages.setAll(cached);
        status.setText(cached.size() + " cached messages in " + folderId);
    }

    private void onMessageSelected(Message message) {
        if (message == null) {
            reader.getEngine().loadContent("<html><body><p>No message selected.</p></body></html>");
            return;
        }
        if (message.unread()) {
            context.store().updateFlags(message.id(), false, message.flagged());
        }
        Message full = context.store().message(message.id()).orElse(message);
        reader.getEngine().loadContent(render(full));
    }

    private String render(Message message) {
        StringBuilder html = new StringBuilder();
        html.append("<html><head><style>body{font-family:sans-serif;font-size:13px;}"
                + "pre{white-space:pre-wrap;word-wrap:break-word;}</style></head><body>");
        html.append("<h2>").append(escape(message.subject())).append("</h2>");
        html.append("<p><b>From:</b> ").append(escape(message.from().name().isBlank()
                ? message.from().email() : message.from().name() + " <" + message.from().email() + ">"))
                .append("</p>");
        if (!message.to().isEmpty()) {
            html.append("<p><b>To:</b> ")
                    .append(escape(message.to().stream()
                            .map(a -> a.email())
                            .reduce((a, b) -> a + ", " + b).orElse("")))
                    .append("</p>");
        }
        html.append("<hr>");
        if (!message.bodyHtml().isBlank()) {
            html.append(HtmlSanitizer.sanitize(message.bodyHtml()));
        } else {
            html.append("<pre>").append(escape(message.bodyText())).append("</pre>");
        }
        html.append("</body></html>");
        return html.toString();
    }

    private void runSearch(String query) {
        Account account = selectedAccount.get();
        if (account == null) {
            return;
        }
        if (query == null || query.isBlank()) {
            loadMessages(account.id(), selectedFolder.get() == null ? "" : selectedFolder.get().id());
            return;
        }
        List<Message> results = context.store().search(
                SearchQuery.builder().text(query).build(), settings.pageSize());
        messages.setAll(results);
        status.setText(results.size() + " results for '" + query + "' (local cache only)");
    }

    private void syncNow() {
        status.setText("Synchronising…");
        Thread worker = new Thread(() -> {
            try {
                var reports = context.syncEngine().syncAll();
                int newMessages = reports.stream().mapToInt(SyncReport::newMessages).sum();
                Platform.runLater(() -> {
                    refreshFolders();
                    status.setText("Synchronised " + reports.size() + " account(s)");
                    if (settings.notificationsEnabled() && newMessages > 0) {
                        notifier.notifyNewMail(newMessages);
                    }
                });
            } catch (RuntimeException e) {
                LOG.warn("Synchronisation failed: {}", e.getMessage());
                Platform.runLater(() -> status.setText("Synchronisation failed: " + e.getMessage()));
            }
        }, "graalmail-sync");
        worker.setDaemon(true);
        worker.start();
    }

    private void installShortcuts() {
        messageList.setOnKeyPressed(event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.R) {
                replyToSelected();
                event.consume();
            }
        });
    }

    private void composeNewMessage() {
        Account account = selectedAccount.get();
        if (account == null) {
            account = firstAccount();
        }
        if (account == null) {
            showInfo("No account is configured yet. Add an account first.");
            return;
        }
        compose.newMessage(account, owner);
    }

    private void replyToSelected() {
        Account account = selectedAccount.get();
        Message message = messageList.getSelectionModel().getSelectedItem();
        if (account == null || message == null) {
            showInfo("Select a message first.");
            return;
        }
        compose.reply(account, message, owner);
    }

    private void forwardSelected() {
        Account account = selectedAccount.get();
        Message message = messageList.getSelectionModel().getSelectedItem();
        if (account == null || message == null) {
            showInfo("Select a message first.");
            return;
        }
        compose.forward(account, message, owner);
    }

    private Account firstAccount() {
        List<Account> accounts = context.store().listAccounts();
        return accounts.isEmpty() ? null : accounts.get(0);
    }

    private void showSettings() {
        new SettingsDialog(settings).show();
    }

    private void showDiagnostics() {
        new DiagnosticsDialog(context).show();
    }

    private void showInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("GraalMail");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void addAccount() {
        Stage stage = new Stage();
        stage.setTitle("Add account");
        AccountSetupDialog dialog = new AccountSetupDialog(context, stage);
        dialog.showAndWait();
        refreshFolders();
    }

    private void showAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("GraalMail");
        alert.setHeaderText("GraalMail desktop");
        alert.setContentText("A cross platform email client. Data directory: "
                + context.paths().baseDirectory());
        alert.showAndWait();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Renders a message row with sender, subject, date and state indicators.
     */
    private static final class MessageListCell extends javafx.scene.control.ListCell<Message> {

        @Override
        protected void updateItem(Message message, boolean empty) {
            super.updateItem(message, empty);
            if (empty || message == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(String.format("%s  %s%s",
                    message.from().name().isBlank() ? message.from().email() : message.from().name(),
                    message.subject(),
                    message.unread() ? "  •" : ""));
            setStyle(message.unread() ? "-fx-font-weight: bold;" : "");
        }
    }

    /**
     * Renders an account or a folder in the tree.
     */
    private static final class FolderTreeCell extends TreeCell<Object> {

        @Override
        protected void updateItem(Object value, boolean empty) {
            super.updateItem(value, empty);
            if (empty || value == null) {
                setText(null);
                return;
            }
            if (value instanceof Account account) {
                setText(account.displayName().isBlank() ? account.email() : account.displayName());
            } else if (value instanceof Folder folder) {
                setText(folder.name() + " (" + folder.unreadCount() + ")");
            } else {
                setText(String.valueOf(value));
            }
        }
    }
}
