/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import com.graalmail.core.domain.Account;
import com.graalmail.core.domain.Folder;
import com.graalmail.core.domain.Message;
import com.graalmail.core.domain.MessageListPage;
import com.graalmail.core.domain.SearchQuery;
import com.graalmail.core.domain.Attachment;
import com.graalmail.core.text.HtmlSanitizer;
import com.graalmail.core.util.Log;
import com.graalmail.storage.MailStore;
import com.graalmail.sync.SyncReport;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.ToolBar;
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
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;
import com.graalmail.storage.PendingAction;
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
    static final Object UNIFIED_INBOX = new Object();

    private final DesktopContext context;
    private final DesktopSettings settings;
    private final Notifier notifier;
    private final ComposeWindow compose;

    private final TreeView<Object> folderTree = new TreeView<>();
    private final ObservableList<Message> messages = FXCollections.observableArrayList();
    private final ListView<Message> messageList = new ListView<>(messages);
    private final WebView reader = new WebView();
    private final TextField searchField = new TextField();
    private final ComboBox<String> searchScope = new ComboBox<>();
    private final Button loadMoreButton = new Button("Load more messages");
    private final Button remoteImagesButton = new Button("Show remote images for this message");
    private final Button readerReplyButton = new Button("Reply");
    private final Button readerForwardButton = new Button("Forward");
    private final Button readerUnreadButton = new Button("Mark unread");
    private final Button readerStarButton = new Button("Star");
    private final Button readerArchiveButton = new Button("Archive");
    private final Button readerJunkButton = new Button("Junk");
    private final Button readerDeleteButton = new Button("Delete");
    private final Button previousButton = new Button("‹ Previous");
    private final Button nextButton = new Button("Next ›");
    private final Button moveButton = new Button("Move to…");
    private final Label readerState = new Label(" ");
    private final javafx.scene.layout.FlowPane attachmentActions = new javafx.scene.layout.FlowPane(8, 8);
    private final Label status = new Label("Ready");
    private final Label viewTitle = new Label("Unified Inbox");
    private final Label messageCount = new Label("0 messages");
    private final Button unreadFilter = new Button("Unread");
    private final Button starredFilter = new Button("Starred");
    private final Button attachmentsFilter = new Button("Attachments");
    private final Button multiSelectButton = new Button("Select multiple");
    private final SimpleObjectProperty<Account> selectedAccount = new SimpleObjectProperty<>();
    private final SimpleObjectProperty<Folder> selectedFolder = new SimpleObjectProperty<>();
    private final SimpleStringProperty searchText = new SimpleStringProperty("");
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(280));
    private final PauseTransition dividerSaveDelay = new PauseTransition(Duration.millis(350));
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "graalmail-ui-io");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger loadGeneration = new AtomicInteger();
    private boolean unreadOnly;
    private boolean flaggedOnly;
    private boolean attachmentsOnly;
    private boolean unifiedInboxSelected = true;
    private SplitPane centerSplit;
    private VBox navigationPane;
    private VBox readerPane;
    private Scene mainScene;
    private boolean remoteImagesAllowed;
    private boolean hasMoreMessages;
    private int pageOffset;
    private Message currentMessage;
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
        root.getStyleClass().add("mail-window");
        if ("Dark".equals(settings.theme())) root.getStyleClass().add("dark-theme");
        VBox top = new VBox(buildMenuBar(), buildToolbar());
        root.setTop(top);
        root.setCenter(buildCenter());
        if (!settings.showNavigation()) centerSplit.getItems().remove(navigationPane);
        if (!settings.showReader()) centerSplit.getItems().remove(readerPane);
        root.setBottom(buildStatusBar());

        folderTree.getSelectionModel().selectedItemProperty().addListener((observable, old, item) ->
                onFolderSelected(item));
        messageList.getSelectionModel().selectedItemProperty().addListener((observable, old, item) ->
                onMessageSelected(item));
        searchField.textProperty().bindBidirectional(searchText);
        searchText.addListener((observable, old, value) -> searchDelay.playFromStart());
        searchDelay.setOnFinished(event -> runSearch(searchText.get()));
        searchScope.valueProperty().addListener((observable, old, value) -> {
            if (!searchText.get().isBlank()) runSearch(searchText.get());
        });
        installShortcuts();
        root.setOnKeyPressed(event -> {
            boolean editingText = event.getTarget() instanceof TextInputControl;
            if (event.isControlDown() && event.getCode() == KeyCode.N) {
                composeNewMessage();
                event.consume();
            } else if (event.isControlDown() && event.getCode() == KeyCode.R) {
                replyToSelected();
                event.consume();
            } else if (event.isControlDown() && event.getCode() == KeyCode.K) {
                searchField.requestFocus();
                searchField.selectAll();
                event.consume();
            } else if (!editingText && event.getCode() == KeyCode.DELETE) {
                deleteSelected();
                event.consume();
            } else if (!editingText && !event.isControlDown() && event.getCode() == KeyCode.A) {
                archiveSelected();
                event.consume();
            }
        });

        refreshFolders();
        Scene scene = new Scene(root, 1280, 820);
        var stylesheet = getClass().getResource("/styles/mail.css");
        if (stylesheet != null) scene.getStylesheets().add(stylesheet.toExternalForm());
        mainScene = scene;
        return scene;
    }

    private ToolBar buildToolbar() {
        Button composeButton = new Button("＋  New message");
        composeButton.getStyleClass().add("primary-action");
        composeButton.setOnAction(event -> composeNewMessage());
        Button refresh = new Button("↻  Sync");
        refresh.setOnAction(event -> syncNow());
        Button reply = new Button("Reply");
        reply.setOnAction(event -> replyToSelected());
        Button forward = new Button("Forward");
        forward.setOnAction(event -> forwardSelected());
        Button markUnread = new Button("Mark unread");
        markUnread.setOnAction(event -> toggleReadSelected());
        Button star = new Button("☆  Star");
        star.setOnAction(event -> toggleStarSelected());
        searchField.setPromptText("Search mail");
        searchField.setPrefWidth(330);
        searchField.setAccessibleText("Search cached mail");
        searchScope.getItems().setAll("All local mail");
        searchScope.setValue("All local mail");
        searchScope.setAccessibleText("Search scope");
        searchScope.setPrefWidth(180);
        Button settingsButton = new Button("Settings");
        settingsButton.setOnAction(event -> showSettings());
        multiSelectButton.setAccessibleText("Toggle multiple message selection");
        multiSelectButton.setOnAction(event -> {
            boolean multiple = messageList.getSelectionModel().getSelectionMode() == SelectionMode.SINGLE;
            messageList.getSelectionModel().setSelectionMode(multiple ? SelectionMode.MULTIPLE : SelectionMode.SINGLE);
            multiSelectButton.setText(multiple ? "Done selecting" : "Select multiple");
            if (!multiple) {
                var selected = messageList.getSelectionModel().getSelectedItem();
                messageList.getSelectionModel().clearSelection();
                if (selected != null) messageList.getSelectionModel().select(selected);
            }
        });
        ToolBar toolbar = new ToolBar(composeButton, refresh, new javafx.scene.control.Separator(),
                reply, forward, markUnread, star, multiSelectButton,
                new javafx.scene.control.Separator(), searchField,
                searchScope, settingsButton);
        toolbar.getStyleClass().add("main-toolbar");
        return toolbar;
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

        Menu view = new Menu("_View");
        CheckMenuItem navigation = new CheckMenuItem("Show folder pane");
        navigation.setSelected(settings.showNavigation());
        navigation.setOnAction(event -> {
            if (navigation.isSelected()) {
                if (!centerSplit.getItems().contains(navigationPane)) centerSplit.getItems().add(0, navigationPane);
            } else centerSplit.getItems().remove(navigationPane);
            settings.showNavigation(navigation.isSelected());
        });
        CheckMenuItem reading = new CheckMenuItem("Show reading pane");
        reading.setSelected(settings.showReader());
        reading.setOnAction(event -> {
            if (reading.isSelected()) {
                if (!centerSplit.getItems().contains(readerPane)) centerSplit.getItems().add(readerPane);
            } else centerSplit.getItems().remove(readerPane);
            settings.showReader(reading.isSelected());
        });
        view.getItems().addAll(navigation, reading);

        Menu help = new Menu("_Help");
        MenuItem about = new MenuItem("_About GraalMail");
        about.setOnAction(event -> showAbout());
        help.getItems().add(about);

        MenuBar bar = new MenuBar(file, view, tools, help);
        bar.setUseSystemMenuBar(true);
        return bar;
    }

    private SplitPane buildCenter() {
        folderTree.setShowRoot(true);
        folderTree.setCellFactory(view -> new FolderTreeCell());
        folderTree.setPrefWidth(245);
        folderTree.setMinWidth(175);

        messageList.setPrefWidth(450);
        messageList.setCellFactory(view -> new MessageListCell(settings.compactDensity()));
        messageList.setPlaceholder(new Label("No messages here yet"));
        ContextMenu messageMenu = new ContextMenu();
        MenuItem reply = new MenuItem("Reply");
        reply.setOnAction(event -> replyToSelected());
        MenuItem unread = new MenuItem("Mark unread / read");
        unread.setOnAction(event -> toggleReadSelected());
        MenuItem star = new MenuItem("Star / unstar");
        star.setOnAction(event -> toggleStarSelected());
        MenuItem delete = new MenuItem("Delete message");
        delete.setOnAction(event -> deleteSelected());
        messageMenu.getItems().addAll(reply, unread, star, delete);
        messageList.setContextMenu(messageMenu);

        unreadFilter.getStyleClass().add("filter-chip");
        starredFilter.getStyleClass().add("filter-chip");
        attachmentsFilter.getStyleClass().add("filter-chip");
        unreadFilter.setOnAction(event -> { unreadOnly = !unreadOnly; setActive(unreadFilter, unreadOnly); reloadCurrentView(); });
        starredFilter.setOnAction(event -> { flaggedOnly = !flaggedOnly; setActive(starredFilter, flaggedOnly); reloadCurrentView(); });
        attachmentsFilter.setOnAction(event -> { attachmentsOnly = !attachmentsOnly; setActive(attachmentsFilter, attachmentsOnly); reloadCurrentView(); });
        HBox filters = new HBox(6, unreadFilter, starredFilter, attachmentsFilter);
        HBox titleRow = new HBox(8, viewTitle, messageCount);
        titleRow.getStyleClass().add("list-title-row");
        loadMoreButton.setOnAction(event -> loadMoreMessages());
        loadMoreButton.setVisible(false);
        loadMoreButton.setManaged(false);
        loadMoreButton.setAccessibleText("Load the next page of messages");
        VBox listPane = new VBox(8, titleRow, filters, messageList, loadMoreButton);
        listPane.getStyleClass().add("message-list-pane");
        VBox.setVgrow(messageList, Priority.ALWAYS);
        listPane.setPadding(new Insets(12));

        readerReplyButton.setOnAction(event -> replyToSelected());
        readerForwardButton.setOnAction(event -> forwardSelected());
        readerUnreadButton.setOnAction(event -> toggleReadSelected());
        readerStarButton.setOnAction(event -> toggleStarSelected());
        readerArchiveButton.setOnAction(event -> archiveSelected());
        readerJunkButton.setOnAction(event -> junkSelected());
        readerDeleteButton.setOnAction(event -> deleteSelected());
        moveButton.setOnAction(event -> showMoveMenu());
        previousButton.setOnAction(event -> selectAdjacent(-1));
        nextButton.setOnAction(event -> selectAdjacent(1));
        remoteImagesButton.setOnAction(event -> {
            remoteImagesAllowed = !remoteImagesAllowed;
            if (currentMessage != null) reader.getEngine().loadContent(render(currentMessage, remoteImagesAllowed));
            remoteImagesButton.setText(remoteImagesAllowed ? "Block remote images" : "Show remote images for this message");
        });
        javafx.scene.layout.FlowPane readerActions = new javafx.scene.layout.FlowPane(
                7, 7, readerReplyButton, readerForwardButton,
                readerArchiveButton, moveButton, readerDeleteButton, readerUnreadButton, readerStarButton,
                readerJunkButton, previousButton, nextButton);
        readerActions.setPadding(new Insets(10));
        readerActions.getStyleClass().add("reader-actions");
        readerReplyButton.setAccessibleText("Reply to selected message");
        readerForwardButton.setAccessibleText("Forward selected message");
        readerArchiveButton.setAccessibleText("Move selected message to Archive");
        readerJunkButton.setAccessibleText("Move selected message to Junk");
        readerDeleteButton.setAccessibleText("Move selected message to Trash or delete");
        previousButton.setAccessibleText("Select previous message");
        nextButton.setAccessibleText("Select next message");
        readerState.getStyleClass().add("reader-state");
        readerState.setPadding(new Insets(0, 12, 6, 12));
        attachmentActions.setPadding(new Insets(0, 10, 8, 10));
        remoteImagesButton.setVisible(false);
        remoteImagesButton.setManaged(false);
        remoteImagesButton.setAccessibleText("Allow remote images for this message only");
        readerPane = new VBox(readerActions, readerState, remoteImagesButton, attachmentActions, reader);
        readerPane.getStyleClass().add("reader-pane");
        reader.setContextMenuEnabled(false);
        reader.setMinWidth(320);
        HBox.setHgrow(reader, Priority.ALWAYS);
        VBox.setVgrow(reader, Priority.ALWAYS);

        navigationPane = new VBox(folderTree);
        navigationPane.setMinWidth(175);
        VBox.setVgrow(folderTree, Priority.ALWAYS);
        centerSplit = new SplitPane(navigationPane, listPane, readerPane);
        centerSplit.setDividerPositions(settings.paneDivider(0, 0.19), settings.paneDivider(1, 0.55));
        dividerSaveDelay.setOnFinished(event -> {
            if (centerSplit.getItems().size() == 3 && centerSplit.getDividerPositions().length == 2) {
                double[] positions = centerSplit.getDividerPositions();
                settings.setPaneDivider(0, positions[0]);
                settings.setPaneDivider(1, positions[1]);
            }
        });
        centerSplit.getDividers().forEach(divider -> divider.positionProperty().addListener(
                (observable, old, value) -> dividerSaveDelay.playFromStart()));
        centerSplit.getStyleClass().add("mail-split-pane");
        updateReaderControls(null);
        return centerSplit;
    }

    private HBox buildStatusBar() {
        Button syncButton = new Button("Synchronise");
        syncButton.setOnAction(event -> syncNow());
        Label connection = new Label("●  Local cache");
        connection.getStyleClass().add("connection-indicator");
        HBox box = new HBox(10, connection, status, syncButton);
        HBox.setHgrow(status, Priority.ALWAYS);
        box.setPadding(new Insets(7, 12, 7, 12));
        box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        box.getStyleClass().add("status-bar");
        return box;
    }

    private void refreshFolders() {
        TreeItem<Object> root = folderTree.getRoot();
        if (root == null) {
            root = new TreeItem<>("Mailboxes");
            folderTree.setRoot(root);
        }
        List<TreeItem<Object>> items = buildTreeItems();
        root.getChildren().setAll(items);
        root.setExpanded(true);
        if (!items.isEmpty()) {
            String lastId = settings.lastAccountId();
            TreeItem<Object> target = items.getFirst();
            if (!lastId.isBlank()) {
                target = items.stream().filter(entry -> entry.getValue() instanceof Account account
                        && account.id().equals(lastId)).findFirst().orElse(target);
            }
            folderTree.getSelectionModel().select(target);
            onFolderSelected(target);
        }
    }

    private List<TreeItem<Object>> buildTreeItems() {
        List<TreeItem<Object>> items = new java.util.ArrayList<>();
        TreeItem<Object> unified = new TreeItem<>(UNIFIED_INBOX);
        items.add(unified);
        for (Account account : context.store().listAccounts()) {
            TreeItem<Object> accountItem = new TreeItem<>(account);
            for (Folder folder : context.store().listFolders(account.id())) {
                accountItem.getChildren().add(new TreeItem<>(folder));
            }
            accountItem.setExpanded(true);
            items.add(accountItem);
        }
        if (items.size() == 1) {
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
        if (value == UNIFIED_INBOX) {
            unifiedInboxSelected = true;
            selectedAccount.set(null);
            selectedFolder.set(null);
            refreshSearchScope();
            viewTitle.setText("Unified Inbox");
            loadMessages(null, null);
        } else if (value instanceof Account account) {
            unifiedInboxSelected = false;
            selectedAccount.set(account);
            refreshSearchScope();
            List<Folder> folders = context.store().listFolders(account.id());
            selectedFolder.set(folders.stream().filter(folder -> folder.id().equals(settings.lastFolderId()))
                    .findFirst().orElseGet(() -> folders.stream()
                            .filter(folder -> folder.type() == com.graalmail.core.domain.FolderType.INBOX)
                            .findFirst().orElse(null)));
            messages.clear();
            status.setText(account.email());
            viewTitle.setText(account.displayName().isBlank() ? account.email() : account.displayName());
            settings.lastAccountId(account.id());
            if (selectedFolder.get() != null) loadMessages(account.id(), selectedFolder.get().id());
        } else if (value instanceof Folder folder) {
            unifiedInboxSelected = false;
            selectedFolder.set(folder);
            Account account = selectedAccount.get();
            if (account != null) {
                refreshSearchScope();
                settings.lastAccountId(account.id());
                settings.lastFolderId(folder.id());
                viewTitle.setText(folder.name());
                loadMessages(account.id(), folder.id());
            }
        }
    }

    private void loadMessages(String accountId, String folderId) {
        int generation = loadGeneration.incrementAndGet();
        loadMoreButton.setDisable(false);
        loadMoreButton.setVisible(false);
        loadMoreButton.setManaged(false);
        status.setText("Loading cached messages…");
        io.submit(() -> {
            try {
                List<Message> cached;
                boolean more;
                int nextOffset;
                if (accountId == null) {
                    cached = context.store().search(SearchQuery.builder().limit(settings.pageSize() + 1).build(),
                            settings.pageSize() + 1);
                    more = cached.size() > settings.pageSize();
                    if (more) cached = new ArrayList<>(cached.subList(0, settings.pageSize()));
                    nextOffset = cached.size();
                } else {
                    MessageListPage page = context.store().listMessages(accountId,
                            folderId == null ? "" : folderId, settings.pageSize(), 0);
                    cached = page.messages();
                    more = page.hasMore();
                    nextOffset = cached.size();
                }
                List<Message> result = applyFilters(cached);
                boolean canLoadMore = more;
                int offset = nextOffset;
                Platform.runLater(() -> {
                    if (generation != loadGeneration.get()) return;
                    messages.setAll(result);
                    pageOffset = offset;
                    hasMoreMessages = canLoadMore;
                    loadMoreButton.setVisible(canLoadMore);
                    loadMoreButton.setManaged(canLoadMore);
                    messageCount.setText(result.size() + " messages");
                    status.setText(result.size() + " cached messages");
                });
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    if (generation == loadGeneration.get()) status.setText("Could not load cached messages: " + e.getMessage());
                });
            }
        });
    }

    private void loadMoreMessages() {
        if (!searchText.get().isBlank()) {
            loadMoreSearch();
            return;
        }
        if (unifiedInboxSelected && hasMoreMessages) {
            loadMoreLocal(null, null);
            return;
        }
        Account account = selectedAccount.get();
        Folder folder = selectedFolder.get();
        if (account == null || folder == null || unifiedInboxSelected || !searchText.get().isBlank()
                || !hasMoreMessages) return;
        loadMoreButton.setDisable(true);
        int generation = loadGeneration.get();
        String accountId = account.id();
        String folderId = folder.id();
        int offset = pageOffset;
        io.submit(() -> {
            try {
                MessageListPage page = context.store().listMessages(accountId, folderId,
                        settings.pageSize(), offset);
                List<Message> result = applyFilters(page.messages());
                Platform.runLater(() -> {
                    if (generation != loadGeneration.get()) return;
                    messages.addAll(result);
                    pageOffset += page.messages().size();
                    hasMoreMessages = page.hasMore();
                    loadMoreButton.setDisable(false);
                    loadMoreButton.setVisible(hasMoreMessages);
                    loadMoreButton.setManaged(hasMoreMessages);
                    messageCount.setText(messages.size() + " messages shown");
                });
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    if (generation != loadGeneration.get()) return;
                    loadMoreButton.setDisable(false);
                    status.setText("Could not load more messages: " + e.getMessage());
                });
            }
        });
    }

    private void loadMoreLocal(String accountId, String folderId) {
        if (!hasMoreMessages) return;
        loadMoreButton.setDisable(true);
        int generation = loadGeneration.get();
        int offset = pageOffset;
        io.submit(() -> {
            try {
                List<Message> page;
                boolean more;
                if (accountId == null) {
                    page = context.store().search(SearchQuery.builder().limit(settings.pageSize() + 1)
                            .offset(offset).build(), settings.pageSize() + 1);
                    more = page.size() > settings.pageSize();
                    if (more) page = new ArrayList<>(page.subList(0, settings.pageSize()));
                } else {
                    MessageListPage result = context.store().listMessages(accountId, folderId,
                            settings.pageSize(), offset);
                    page = result.messages();
                    more = result.hasMore();
                }
                List<Message> filtered = applyFilters(page);
                int count = page.size();
                boolean canLoadMore = more;
                Platform.runLater(() -> {
                    if (generation != loadGeneration.get()) return;
                    messages.addAll(filtered);
                    pageOffset += count;
                    hasMoreMessages = canLoadMore;
                    loadMoreButton.setDisable(false);
                    loadMoreButton.setVisible(canLoadMore);
                    loadMoreButton.setManaged(canLoadMore);
                    messageCount.setText(messages.size() + " messages shown");
                });
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    if (generation == loadGeneration.get()) {
                        loadMoreButton.setDisable(false);
                        status.setText("Could not load more messages: " + e.getMessage());
                    }
                });
            }
        });
    }

    private void loadMoreSearch() {
        if (!hasMoreMessages) return;
        loadMoreButton.setDisable(true);
        runSearchPage(searchText.get(), pageOffset, true);
    }

    private void refreshSearchScope() {
        String current = searchScope.getValue();
        searchScope.getItems().setAll("All local mail");
        Account account = selectedAccount.get();
        if (account != null) {
            searchScope.getItems().addAll("This account (local)", "This folder (local)");
            try {
                if (context.sessions().provider(account).capabilities().serverSideSearch()) {
                    searchScope.getItems().add("Current account (server)");
                }
            } catch (RuntimeException e) {
                LOG.debug("Search capability unavailable for account {}", account.id());
            }
        }
        searchScope.setValue(searchScope.getItems().contains(current) ? current : "All local mail");
    }

    private void onMessageSelected(Message message) {
        currentMessage = message;
        updateReaderControls(message);
        attachmentActions.getChildren().clear();
        if (message == null) {
            readerState.setText(" ");
            remoteImagesAllowed = false;
            remoteImagesButton.setVisible(false);
            remoteImagesButton.setManaged(false);
            reader.getEngine().loadContent("<html><body><p>Select a message to read it.</p></body></html>");
            return;
        }
        remoteImagesAllowed = false;
        if (message.unread()) {
            context.store().account(message.accountId()).ifPresent(account ->
                    context.syncEngine().queue(account, PendingAction.Type.MARK_READ, message.id(), ""));
            int index = messageList.getSelectionModel().getSelectedIndex();
            messages.set(index, message.toBuilder().unread(false).build());
        }
        Message full = context.store().message(message.id()).orElse(message);
        currentMessage = full;
        readerState.setText(pendingActionStatus(full));
        boolean remoteImages = containsRemoteImages(full.bodyHtml());
        remoteImagesButton.setText("Show remote images for this message");
        remoteImagesButton.setVisible(remoteImages);
        remoteImagesButton.setManaged(remoteImages);
        reader.getEngine().loadContent(render(full, false));
        showAttachments(full);
    }

    private String render(Message message) {
        return MessageBodyRenderer.render(message, false, "Dark".equals(settings.theme()));
    }

    private String render(Message message, boolean allowRemoteImages) {
        return MessageBodyRenderer.render(message, allowRemoteImages, "Dark".equals(settings.theme()));
    }

    private String pendingActionStatus(Message message) {
        return context.store().pendingActions(message.accountId()).stream()
                .filter(action -> action.targetId().equals(message.id()))
                .map(action -> action.state() == PendingAction.State.FAILED
                        ? "A mail action failed: " + action.lastError()
                        : "This change is waiting to synchronise")
                .findFirst().orElse(" ");
    }

    private static boolean containsRemoteImages(String html) {
        return html != null && java.util.regex.Pattern.compile(
                "(?is)<img\\b[^>]*\\bsrc\\s*=\\s*['\\\"]?https?://").matcher(html).find();
    }

    private void showAttachments(Message message) {
        if (message.attachments().isEmpty() || !capabilities(message.accountId()).attachments()) return;
        Label title = new Label("Attachments:");
        attachmentActions.getChildren().add(title);
        for (Attachment attachment : message.attachments()) {
            Button download = new Button("⬇  " + attachment.fileName() + "  ("
                    + com.graalmail.core.text.TextTools.formatBytes(attachment.size()) + ")");
            download.setAccessibleText("Save attachment " + attachment.fileName());
            download.setTooltip(new javafx.scene.control.Tooltip("Save " + attachment.fileName()
                    + " (" + attachment.mimeType() + ")"));
            download.setOnAction(event -> downloadAttachment(message, attachment));
            attachmentActions.getChildren().add(download);
        }
    }

    private void downloadAttachment(Message message, Attachment attachment) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save attachment");
        chooser.setInitialFileName(attachment.fileName());
        java.io.File target = chooser.showSaveDialog(owner);
        if (target == null) return;
        Account account = context.store().account(message.accountId()).orElse(null);
        if (account == null) return;
        status.setText("Downloading “" + attachment.fileName() + "”…");
        io.submit(() -> {
            try (var session = context.sessions().open(account)) {
                session.attachments().download(attachment, target.toPath());
                Platform.runLater(() -> status.setText("Saved attachment to " + target.getAbsolutePath()));
            } catch (RuntimeException e) {
                Platform.runLater(() -> status.setText("Attachment download failed: " + e.getMessage()));
            }
        });
    }

    private void updateReaderControls(Message message) {
        boolean selected = message != null;
        boolean flags = selected && capabilities(message.accountId()).flags();
        readerReplyButton.setDisable(!selected);
        readerForwardButton.setDisable(!selected);
        readerArchiveButton.setDisable(!selected || context.store().listFolders(message.accountId()).stream()
                .noneMatch(folder -> folder.type() == com.graalmail.core.domain.FolderType.ARCHIVE));
        readerJunkButton.setDisable(!selected || context.store().listFolders(message.accountId()).stream()
                .noneMatch(folder -> folder.type() == com.graalmail.core.domain.FolderType.SPAM));
        moveButton.setDisable(!selected);
        readerDeleteButton.setDisable(!selected);
        readerUnreadButton.setDisable(!flags);
        readerStarButton.setDisable(!flags);
        previousButton.setDisable(!selected || messageList.getSelectionModel().getSelectedIndex() <= 0);
        nextButton.setDisable(!selected || messageList.getSelectionModel().getSelectedIndex() < 0
                || messageList.getSelectionModel().getSelectedIndex() >= messages.size() - 1);
        if (selected) {
            readerUnreadButton.setText(message.unread() ? "Mark read" : "Mark unread");
            readerStarButton.setText(message.flagged() ? "Unstar" : "Star");
        } else {
            readerUnreadButton.setText("Mark unread");
            readerStarButton.setText("Star");
        }
    }

    private com.graalmail.provider.api.ProviderCapabilities capabilities(String accountId) {
        try {
            return context.store().account(accountId)
                    .map(account -> context.sessions().provider(account).capabilities())
                    .orElseGet(() -> com.graalmail.provider.api.ProviderCapabilities
                            .builder(com.graalmail.provider.api.ProviderType.GENERIC).build());
        } catch (RuntimeException e) {
            return com.graalmail.provider.api.ProviderCapabilities
                    .builder(com.graalmail.provider.api.ProviderType.GENERIC).flags(true).attachments(true).build();
        }
    }

    private void selectAdjacent(int offset) {
        int index = messageList.getSelectionModel().getSelectedIndex() + offset;
        if (index >= 0 && index < messages.size()) messageList.getSelectionModel().select(index);
    }

    private void archiveSelected() {
        Message message = messageList.getSelectionModel().getSelectedItem();
        if (message == null) return;
        Account account = context.store().account(message.accountId()).orElse(null);
        if (account == null) return;
        Folder archive = context.store().listFolders(account.id()).stream()
                .filter(folder -> folder.type() == com.graalmail.core.domain.FolderType.ARCHIVE)
                .findFirst().orElse(null);
        if (archive == null) {
            status.setText("This account does not expose an Archive folder");
            return;
        }
        context.syncEngine().queue(account, PendingAction.Type.MOVE, message.id(), archive.id());
        removeSelectedFromCurrentList("Message moved to Archive; change queued for synchronisation");
    }

    private void junkSelected() {
        Message message = messageList.getSelectionModel().getSelectedItem();
        if (message == null) return;
        Account account = context.store().account(message.accountId()).orElse(null);
        if (account == null) return;
        Folder spam = context.store().listFolders(account.id()).stream()
                .filter(folder -> folder.type() == com.graalmail.core.domain.FolderType.SPAM)
                .findFirst().orElse(null);
        if (spam == null) {
            status.setText("This account does not expose a Junk folder");
            return;
        }
        context.syncEngine().queue(account, PendingAction.Type.MOVE, message.id(), spam.id());
        removeSelectedFromCurrentList("Message moved to Junk; change queued for synchronisation");
    }

    private void showMoveMenu() {
        Message message = messageList.getSelectionModel().getSelectedItem();
        if (message == null) return;
        Account account = context.store().account(message.accountId()).orElse(null);
        if (account == null) return;
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
        for (Folder folder : context.store().listFolders(account.id())) {
            if (!folder.selectable() || folder.id().equals(message.folderId())) continue;
            MenuItem destination = new MenuItem(folder.name());
            destination.setOnAction(event -> {
                context.syncEngine().queue(account, PendingAction.Type.MOVE, message.id(), folder.id());
                removeSelectedFromCurrentList("Move to " + folder.name() + " queued for synchronisation");
            });
            menu.getItems().add(destination);
        }
        if (menu.getItems().isEmpty()) menu.getItems().add(new MenuItem("No other folders available"));
        menu.show(moveButton, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    private void removeSelectedFromCurrentList(String message) {
        Message selected = messageList.getSelectionModel().getSelectedItem();
        if (selected != null) messages.remove(selected);
        messageCount.setText(messages.size() + " messages");
        onMessageSelected(null);
        status.setText(message);
    }

    private void runSearch(String query) {
        if (query == null || query.isBlank()) {
            reloadCurrentView();
            return;
        }
        runSearchPage(query, 0, false);
    }

    private void runSearchPage(String query, int offset, boolean append) {
        int generation = append ? loadGeneration.get() : loadGeneration.incrementAndGet();
        boolean serverSearch = "Current account (server)".equals(searchScope.getValue());
        Account account = selectedAccount.get();
        Folder folder = selectedFolder.get();
        if (serverSearch && account == null) {
            searchScope.setValue("All local mail");
            return;
        }
        status.setText(serverSearch ? "Searching provider…" : "Searching local cache…");
        io.submit(() -> {
            try {
                SearchQuery.Builder queryBuilder = SearchQuery.builder().text(query)
                        .limit(settings.pageSize() + 1).offset(offset);
                String scope = searchScope.getValue();
                if ("This account (local)".equals(scope) && account != null) {
                    queryBuilder.accountIds(List.of(account.id()));
                } else if ("This folder (local)".equals(scope) && account != null
                        && folder != null) {
                    queryBuilder.accountIds(List.of(account.id()))
                            .folderIds(List.of(folder.id()));
                }
                SearchQuery searchQuery = queryBuilder.build();
                List<Message> results;
                boolean remote = false;
                if (serverSearch) {
                    SearchQuery providerQuery = queryBuilder.limit(offset + settings.pageSize() + 1)
                            .offset(0).build();
                    try (var session = context.sessions().open(account)) {
                        List<Message> providerResults = session.search().search(providerQuery);
                        remote = session.search().isServerSide();
                        int from = Math.min(offset, providerResults.size());
                        int to = Math.min(providerResults.size(), offset + settings.pageSize() + 1);
                        results = new ArrayList<>(providerResults.subList(from, to));
                    }
                } else {
                    results = context.store().search(searchQuery, settings.pageSize() + 1);
                }
                boolean more = results.size() > settings.pageSize();
                if (more) results = new ArrayList<>(results.subList(0, settings.pageSize()));
                List<Message> filtered = applyFilters(results);
                int returnedCount = results.size();
                boolean serverResults = remote;
                boolean canLoadMore = more;
                Platform.runLater(() -> {
                    if (generation != loadGeneration.get()) return;
                    if (append) messages.addAll(filtered);
                    else messages.setAll(filtered);
                    pageOffset = offset + returnedCount;
                    hasMoreMessages = canLoadMore;
                    loadMoreButton.setDisable(false);
                    loadMoreButton.setVisible(canLoadMore);
                    loadMoreButton.setManaged(canLoadMore);
                    messageCount.setText(messages.size() + " results");
                    status.setText(messages.size() + (serverResults ? " provider results" : " local results")
                            + " for “" + query + "”");
                });
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    if (generation == loadGeneration.get()) {
                        loadMoreButton.setDisable(false);
                        status.setText("Search failed: " + e.getMessage());
                    }
                });
            }
        });
    }

    private List<Message> applyFilters(List<Message> source) {
        return source.stream()
                .filter(m -> !unreadOnly || m.unread())
                .filter(m -> !flaggedOnly || m.flagged())
                .filter(m -> !attachmentsOnly || m.hasAttachments())
                .toList();
    }

    private void reloadCurrentView() {
        if (!searchText.get().isBlank()) {
            runSearch(searchText.get());
        } else if (unifiedInboxSelected) {
            loadMessages(null, null);
        } else {
            Account account = selectedAccount.get();
            if (account != null) loadMessages(account.id(), selectedFolder.get() == null ? "" : selectedFolder.get().id());
        }
    }

    private void setActive(Button button, boolean active) {
        if (active) button.getStyleClass().add("active");
        else button.getStyleClass().remove("active");
    }

    private void toggleReadSelected() {
        List<Message> selected = selectedMessages();
        if (selected.isEmpty()) return;
        for (Message message : selected) {
            Account account = context.store().account(message.accountId()).orElse(null);
            if (account == null) continue;
            PendingAction.Type type = message.unread() ? PendingAction.Type.MARK_READ : PendingAction.Type.MARK_UNREAD;
            context.syncEngine().queue(account, type, message.id(), "");
            updateVisibleMessage(message.toBuilder().unread(!message.unread()).build());
        }
        status.setText(selected.size() == 1 ? "Read state queued for synchronisation"
                : selected.size() + " read-state changes queued for synchronisation");
    }

    private void toggleStarSelected() {
        List<Message> selected = selectedMessages();
        if (selected.isEmpty()) return;
        for (Message message : selected) {
            Account account = context.store().account(message.accountId()).orElse(null);
            if (account == null) continue;
            context.syncEngine().queue(account, message.flagged()
                    ? PendingAction.Type.UNFLAG : PendingAction.Type.FLAG, message.id(), "");
            context.store().updateFlags(message.id(), message.unread(), !message.flagged());
            updateVisibleMessage(message.toBuilder().flagged(!message.flagged()).build());
        }
        status.setText(selected.size() == 1 ? "Star change queued for synchronisation"
                : selected.size() + " star changes queued for synchronisation");
    }

    private List<Message> selectedMessages() {
        List<Message> selected = new ArrayList<>(messageList.getSelectionModel().getSelectedItems());
        if (selected.isEmpty() && messageList.getSelectionModel().getSelectedItem() != null)
            selected.add(messageList.getSelectionModel().getSelectedItem());
        return selected;
    }

    private void deleteSelected() {
        List<Message> selected = new ArrayList<>(messageList.getSelectionModel().getSelectedItems());
        if (selected.isEmpty() && messageList.getSelectionModel().getSelectedItem() != null)
            selected.add(messageList.getSelectionModel().getSelectedItem());
        if (selected.isEmpty()) return;
        boolean permanent = selected.stream().anyMatch(message -> context.store().listFolders(message.accountId()).stream()
                .noneMatch(folder -> folder.type() == com.graalmail.core.domain.FolderType.TRASH));
        if (permanent) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "At least one selected account has no Trash folder. Delete those messages permanently?");
            confirm.setTitle("Delete message");
            confirm.setHeaderText(null);
            if (confirm.showAndWait().filter(button -> button == javafx.scene.control.ButtonType.OK).isEmpty()) return;
        }
        int movedToTrash = 0;
        for (Message message : selected) {
            Account account = context.store().account(message.accountId()).orElse(null);
            if (account == null) continue;
            Folder trash = context.store().listFolders(account.id()).stream()
                    .filter(folder -> folder.type() == com.graalmail.core.domain.FolderType.TRASH)
                    .findFirst().orElse(null);
            if (trash == null) {
                context.syncEngine().queue(account, PendingAction.Type.DELETE, message.id(), "");
            } else {
                context.syncEngine().queue(account, PendingAction.Type.MOVE, message.id(), trash.id());
                movedToTrash++;
            }
        }
        messages.removeAll(selected);
        messageCount.setText(messages.size() + " messages");
        onMessageSelected(null);
        status.setText(selected.size() == 1
                ? (movedToTrash == 1 ? "Message moved to Trash; change queued for synchronisation"
                : "Message deletion queued for synchronisation")
                : selected.size() + " message actions queued for synchronisation");
    }

    private void updateVisibleMessage(Message updated) {
        int index = messages.indexOf(messageList.getSelectionModel().getSelectedItem());
        if (index >= 0) messages.set(index, updated);
        onMessageSelected(updated);
    }

    public void close() {
        searchDelay.stop();
        dividerSaveDelay.stop();
        io.shutdownNow();
    }

    private void syncNow() {
        status.setText("Synchronising…");
        Thread worker = new Thread(() -> {
            try {
                var reports = context.syncEngine().syncAll();
                int newMessages = reports.stream().mapToInt(SyncReport::newMessages).sum();
                Platform.runLater(() -> {
                    refreshFolders();
                    int errors = reports.stream().mapToInt(report -> report.errors().size()).sum();
                    status.setText(errors == 0 ? "Synchronised " + reports.size() + " account(s)"
                            : "Sync finished with " + errors + " issue(s)");
                    reloadCurrentView();
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
        Message message = messageList.getSelectionModel().getSelectedItem();
        Account account = message == null ? null : context.store().account(message.accountId()).orElse(null);
        if (account == null) {
            showInfo("Select a message first.");
            return;
        }
        compose.reply(account, message, owner);
    }

    private void forwardSelected() {
        Message message = messageList.getSelectionModel().getSelectedItem();
        Account account = message == null ? null : context.store().account(message.accountId()).orElse(null);
        if (account == null) {
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
        messageList.setCellFactory(view -> new MessageListCell(settings.compactDensity()));
        if (mainScene != null) {
            if ("Dark".equals(settings.theme())) {
                if (!mainScene.getRoot().getStyleClass().contains("dark-theme"))
                    mainScene.getRoot().getStyleClass().add("dark-theme");
            } else mainScene.getRoot().getStyleClass().remove("dark-theme");
        }
        if (currentMessage != null) reader.getEngine().loadContent(render(currentMessage, remoteImagesAllowed));
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

}
