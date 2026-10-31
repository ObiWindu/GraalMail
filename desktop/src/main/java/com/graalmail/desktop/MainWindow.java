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
import com.graalmail.core.domain.Address;
import com.graalmail.core.domain.Attachment;
import com.graalmail.core.text.HtmlSanitizer;
import com.graalmail.core.util.Log;
import com.graalmail.storage.MailStore;
import com.graalmail.sync.SyncReport;
import java.util.List;
import java.util.ArrayList;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
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
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.control.ListView;
import javafx.scene.control.TableView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.MenuItem;
import javafx.scene.control.MenuButton;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToolBar;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
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
    private final TableView<Message> messageTable = new TableView<>(messages);
    private final ComboBox<String> listModePicker = new ComboBox<>();
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
    private final Button previousButton = new Button("Previous");
    private final Button nextButton = new Button("Next");
    private final Button moveButton = new Button("Move to…");
    private final Label readerState = new Label(" ");
    private final Label readerSubject = new Label();
    private final Label readerSender = new Label();
    private final Label readerDate = new Label();
    private final Label readerRecipientSummary = new Label();
    private final Label readerRecipientDetails = new Label();
    private final Label readerMessageIndicators = new Label();
    private final javafx.scene.layout.StackPane readerAvatarHost = new javafx.scene.layout.StackPane();
    private final Button readerDetailsToggle = new Button("Show details");
    private VBox readerHeader;
    private final javafx.scene.layout.FlowPane attachmentActions = new javafx.scene.layout.FlowPane(8, 8);
    private final Label status = new Label("Ready");
    private final Label messageCountLabel = new Label();
    private final Label polyMailStatus = new Label("");
    private DesktopPolyMailService polyMail;
    private final Label viewTitle = new Label("Unified Inbox");
    private final Label messageCount = new Label("0 messages");
    private final ToggleButton unreadFilter = new ToggleButton("Unread");
    private final ToggleButton starredFilter = new ToggleButton("Starred");
    private final ToggleButton attachmentsFilter = new ToggleButton("Attachments");
    private final Button clearFiltersButton = new Button("Clear filters");
    private final Button quickFilterToggle = new Button("Filter");
    private final Button multiSelectButton = new Button("Select");
    private final Label selectionCount = new Label("0 selected");
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
    private BorderPane workspaceHost;
    private Node listPane;
    private VBox navigationPane;
    private VBox readerPane;
    private javafx.scene.layout.FlowPane readerActionsPane;
    private String activeLayoutMode;
    private Scene mainScene;
    private boolean remoteImagesAllowed;
    private boolean synchronizingSelection;
    private boolean hasMoreMessages;
    private int pageOffset;
    private int readerGeneration;
    private Message currentMessage;
    private Stage owner;
    private static final java.util.concurrent.atomic.AtomicBoolean FIRST_RUN_PROMPTED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

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
        if (isDarkTheme()) root.getStyleClass().add("dark-theme");
        // Proton-style layout: single top bar + three-pane workspace.
        // The legacy Thunderbird spaces rail is no longer shown.
        BorderPane mailShell = new BorderPane();
        mailShell.getStyleClass().add("mail-shell");
        VBox top = new VBox(buildMenuBar(), buildToolbar());
        mailShell.setTop(top);
        mailShell.setCenter(buildCenter());
        mailShell.setBottom(buildStatusBar());
        root.setCenter(mailShell);

        folderTree.getSelectionModel().selectedItemProperty().addListener((observable, old, item) ->
                onFolderSelected(item));
        messages.addListener((javafx.collections.ListChangeListener<Message>) change -> updateMessageCount());
        updateMessageCount();
        messageList.getSelectionModel().selectedItemProperty().addListener((observable, old, item) -> {
            if (synchronizingSelection) return;
            synchronizeSelection(messageList, messageTable);
            onMessageSelected(item);
            updateSelectionControls();
        });
        messageTable.getSelectionModel().selectedItemProperty().addListener((observable, old, item) -> {
            if (synchronizingSelection) return;
            synchronizeSelection(messageTable, messageList);
            onMessageSelected(item);
            updateSelectionControls();
        });
        searchField.textProperty().bindBidirectional(searchText);
        searchText.addListener((observable, old, value) -> searchDelay.playFromStart());
        searchDelay.setOnFinished(event -> runSearch(searchText.get()));
        searchScope.valueProperty().addListener((observable, old, value) -> {
            if (!searchText.get().isBlank()) runSearch(searchText.get());
        });
        installShortcuts();
        root.setOnKeyPressed(event -> {
            boolean editingText = event.getTarget() instanceof TextInputControl
                    || (event.getTarget() instanceof Node target && isInsideReader(target));
            if (editingText) return;
            boolean singleKeySafe = event.getTarget() == messageList
                    || event.getTarget() == messageTable
                    || (event.getTarget() instanceof Node target
                    && (isInsideMessageList(target) || target == root));
            if (event.isControlDown() && event.getCode() == KeyCode.N) {
                composeNewMessage();
                event.consume();
            } else if (event.isControlDown() && !event.isShiftDown() && event.getCode() == KeyCode.R) {
                replyToSelected();
                event.consume();
            } else if (event.isControlDown() && event.getCode() == KeyCode.K) {
                searchField.requestFocus();
                searchField.selectAll();
                event.consume();
            } else if (event.isControlDown() && event.isShiftDown() && event.getCode() == KeyCode.F) {
                forwardSelected();
                event.consume();
            } else if (event.isControlDown() && event.isShiftDown() && event.getCode() == KeyCode.R) {
                syncNow();
                event.consume();
            } else if (event.getCode() == KeyCode.DELETE
                    || (!event.isControlDown() && !event.isMetaDown() && event.getCode() == KeyCode.NUMBER_SIGN)) {
                deleteSelected();
                event.consume();
            } else if (!event.isControlDown() && !event.isMetaDown() && event.getCode() == KeyCode.SLASH) {
                searchField.requestFocus();
                searchField.selectAll();
                event.consume();
            } else if (singleKeySafe && !event.isControlDown() && !event.isMetaDown() && event.getCode() == KeyCode.C) {
                composeNewMessage();
                event.consume();
            } else if (singleKeySafe && !event.isControlDown() && !event.isMetaDown() && event.getCode() == KeyCode.R) {
                replyToSelected();
                event.consume();
            } else if (singleKeySafe && !event.isControlDown() && !event.isMetaDown() && event.getCode() == KeyCode.F) {
                forwardSelected();
                event.consume();
            } else if (singleKeySafe && !event.isControlDown() && !event.isMetaDown() && event.getCode() == KeyCode.ENTER) {
                reader.requestFocus();
                event.consume();
            } else if (singleKeySafe && !event.isControlDown() && !event.isMetaDown() && event.getCode() == KeyCode.E) {
                archiveSelected();
                event.consume();
            } else if (singleKeySafe && !event.isControlDown() && !event.isMetaDown() && event.getCode() == KeyCode.U) {
                toggleReadSelected();
                event.consume();
            } else if (singleKeySafe && !event.isControlDown() && !event.isMetaDown() && event.getCode() == KeyCode.S) {
                toggleStarSelected();
                event.consume();
            } else if (singleKeySafe && !event.isControlDown() && !event.isMetaDown() && event.getCode() == KeyCode.J) {
                selectAdjacent(1);
                event.consume();
            } else if (singleKeySafe && !event.isControlDown() && !event.isMetaDown() && event.getCode() == KeyCode.K) {
                selectAdjacent(-1);
                event.consume();
            } else if (singleKeySafe && !event.isControlDown() && !event.isMetaDown() && event.getCode() == KeyCode.A) {
                archiveSelected();
                event.consume();
            } else if (singleKeySafe && !event.isControlDown() && !event.isMetaDown() && event.getCode() == KeyCode.OPEN_BRACKET) {
                settings.showNavigation(!settings.showNavigation());
                rebuildWorkspace();
                event.consume();
            }
        });

        refreshFolders();
        if (context.store().listAccounts().isEmpty()
                && FIRST_RUN_PROMPTED.compareAndSet(false, true)) {
            status.setText("Welcome to GraalMail — add your first account to get started.");
            javafx.application.Platform.runLater(this::addAccount);
        }
        Scene scene = new Scene(root);
        var stylesheet = getClass().getResource("/styles/mail.css");
        if (stylesheet != null) scene.getStylesheets().add(stylesheet.toExternalForm());
        mainScene = scene;
        if (owner != null) {
            UiScale.install(scene, owner);
            UiScale.onScale(owner, this::refreshScaledContent);
            reader.setZoom(UiScale.scale(owner));
        }
        scene.widthProperty().addListener((observable, oldWidth, newWidth) -> {
            if (!effectiveLayoutMode().equals(activeLayoutMode)) rebuildWorkspace();
        });
        javafx.application.Platform.runLater(this::startPolyMail);
        return scene;
    }

    private MenuBar buildMenuBar() {
        MenuBar bar = new MenuBar();
        bar.getStyleClass().add("mail-menu-bar");

        Menu file = new Menu("File");
        MenuItem newMsg = new MenuItem("New Message");
        newMsg.setAccelerator(new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN));
        newMsg.setOnAction(e -> composeNewMessage());
        MenuItem getMail = new MenuItem("Get Mail");
        getMail.setAccelerator(new KeyCodeCombination(KeyCode.R, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN));
        getMail.setOnAction(e -> syncNow());
        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(e -> { if (owner != null) owner.close(); });
        file.getItems().addAll(newMsg, getMail, new SeparatorMenuItem(), exit);

        Menu edit = new Menu("Edit");
        MenuItem find = new MenuItem("Find Messages");
        find.setAccelerator(new KeyCodeCombination(KeyCode.K, KeyCombination.CONTROL_DOWN));
        find.setOnAction(e -> { searchField.requestFocus(); searchField.selectAll(); });
        edit.getItems().add(find);

        Menu view = new Menu("View");
        Menu layoutMenu = new Menu("Layout");
        for (String mode : List.of("Classic", "Wide", "Vertical")) {
            RadioMenuItem choice = new RadioMenuItem(mode);
            choice.setSelected(mode.equals(settings.layoutMode()));
            choice.setOnAction(e -> { settings.layoutMode(mode); rebuildWorkspace(); });
            layoutMenu.getItems().add(choice);
        }
        ToggleGroup tg = new ToggleGroup();
        for (javafx.scene.control.MenuItem mi : layoutMenu.getItems()) ((RadioMenuItem) mi).setToggleGroup(tg);
        CheckMenuItem showFolders = new CheckMenuItem("Show Folder Pane");
        showFolders.setSelected(settings.showNavigation());
        showFolders.setOnAction(e -> { settings.showNavigation(showFolders.isSelected()); rebuildWorkspace(); });
        CheckMenuItem showReader = new CheckMenuItem("Show Reading Pane");
        showReader.setSelected(settings.showReader());
        showReader.setOnAction(e -> { settings.showReader(showReader.isSelected()); rebuildWorkspace(); });
        view.getItems().addAll(layoutMenu, showFolders, showReader);

        Menu go = new Menu("Go");
        MenuItem next = new MenuItem("Next Message");
        next.setOnAction(e -> selectAdjacent(1));
        MenuItem prev = new MenuItem("Previous Message");
        prev.setOnAction(e -> selectAdjacent(-1));
        go.getItems().addAll(next, prev);

        Menu folder = new Menu("Folder");
        MenuItem updateFolder = new MenuItem("Update Folder");
        updateFolder.setOnAction(e -> syncNow());
        MenuItem markAllRead = new MenuItem("Mark All as Read");
        markAllRead.setOnAction(e -> {
            Account a = selectedAccount.get();
            Folder f = selectedFolder.get();
            if (a != null && f != null) markFolderRead(a, f);
        });
        folder.getItems().addAll(updateFolder, markAllRead);

        Menu message = new Menu("Message");
        MenuItem reply = new MenuItem("Reply");
        reply.setOnAction(e -> replyToSelected());
        MenuItem replyAll = new MenuItem("Reply to All");
        replyAll.setOnAction(e -> replyToSelected());
        MenuItem forward = new MenuItem("Forward");
        forward.setOnAction(e -> forwardSelected());
        MenuItem archive = new MenuItem("Archive");
        archive.setOnAction(e -> archiveSelected());
        MenuItem del = new MenuItem("Delete");
        del.setOnAction(e -> deleteSelected());
        MenuItem markRead = new MenuItem("Mark as Read/Unread");
        markRead.setOnAction(e -> toggleReadSelected());
        MenuItem star = new MenuItem("Star Message");
        star.setOnAction(e -> toggleStarSelected());
        MenuItem junk = new MenuItem("Mark as Junk");
        junk.setOnAction(e -> junkSelected());
        MenuItem move = new MenuItem("Move to Folder…");
        move.setOnAction(e -> showMoveMenu());
        message.getItems().addAll(reply, replyAll, forward, new SeparatorMenuItem(),
                archive, del, junk, move, new SeparatorMenuItem(), markRead, star);

        Menu tools = new Menu("Tools");
        MenuItem addAccount = new MenuItem("Add Account…");
        addAccount.setOnAction(e -> addAccount());
        MenuItem diagnostics = new MenuItem("Diagnostics…");
        diagnostics.setOnAction(e -> showDiagnostics());
        tools.getItems().addAll(addAccount, diagnostics);

        Menu settingsMenu = new Menu("Settings");
        MenuItem settingsItem = new MenuItem("Configure GraalMail…");
        settingsItem.setOnAction(e -> showSettings());
        settingsMenu.getItems().add(settingsItem);

        Menu help = new Menu("Help");
        MenuItem about = new MenuItem("About GraalMail");
        about.setOnAction(e -> status.setText("GraalMail — KMail-inspired desktop mail client"));
        help.getItems().add(about);

        bar.getMenus().addAll(file, edit, view, go, folder, message, tools, settingsMenu, help);
        return bar;
    }

    private ToolBar buildToolbar() {
        // Proton-style top bar: logo + compose on the left, pill search in the
        // center, sync / layout / settings on the right.
        Label logoMark = new Label("G");
        logoMark.getStyleClass().add("proton-logo-mark");
        Label logoName = new Label("GraalMail");
        logoName.getStyleClass().add("proton-logo-name");
        HBox brand = new HBox(UiScale.px(owner, 8), logoMark, logoName);
        brand.getStyleClass().add("proton-brand");
        brand.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        Button navToggle = new Button();
        navToggle.setGraphic(ThunderbirdIcons.icon("spaces-menu", "toolbar-icon"));
        navToggle.setAccessibleText("Toggle folder pane");
        navToggle.setTooltip(new javafx.scene.control.Tooltip("Toggle folders ( [ )"));
        navToggle.getStyleClass().add("icon-button");
        navToggle.setOnAction(event -> {
            settings.showNavigation(!settings.showNavigation());
            rebuildWorkspace();
        });

        Button composeButton = new Button("New message");
        composeButton.setGraphic(ThunderbirdIcons.icon("new-mail", "toolbar-icon"));
        composeButton.getStyleClass().add("primary-action");
        composeButton.getStyleClass().add("proton-compose");
        composeButton.setTooltip(new javafx.scene.control.Tooltip("New message (C)"));
        composeButton.setAccessibleText("New message (C)");
        composeButton.setOnAction(event -> composeNewMessage());

        searchField.setPromptText("Search messages…  ( / )");
        searchField.setAccessibleText("Search cached mail");
        HBox searchBox = new HBox(UiScale.px(owner, 6), ThunderbirdIcons.icon("search", "toolbar-icon"), searchField);
        searchBox.getStyleClass().add("toolbar-search");
        searchBox.getStyleClass().add("proton-search");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        HBox.setHgrow(searchBox, Priority.ALWAYS);
        searchField.focusedProperty().addListener((observable, wasFocused, focused) -> {
            if (focused) searchBox.getStyleClass().add("search-focused");
            else searchBox.getStyleClass().remove("search-focused");
        });
        searchScope.getItems().setAll("All local mail");
        searchScope.setValue("All local mail");
        searchScope.setId("search-scope");
        searchScope.setAccessibleText("Search scope: all local mail");
        searchScope.setTooltip(new javafx.scene.control.Tooltip("Search scope"));

        Button refresh = new Button();
        refresh.setGraphic(ThunderbirdIcons.icon("get-mail", "toolbar-icon"));
        refresh.setAccessibleText("Get messages for all accounts");
        refresh.setTooltip(new javafx.scene.control.Tooltip("Get messages (Ctrl+Shift+R)"));
        refresh.getStyleClass().add("icon-button");
        refresh.setOnAction(event -> syncNow());

        MenuButton layoutButton = new MenuButton();
        layoutButton.setGraphic(ThunderbirdIcons.icon("display-options", "toolbar-icon"));
        layoutButton.setAccessibleText("Choose workspace layout and pane visibility");
        layoutButton.setTooltip(new javafx.scene.control.Tooltip("Layout"));
        layoutButton.getStyleClass().add("icon-button");
        ToggleGroup layouts = new ToggleGroup();
        for (String mode : List.of("Classic", "Wide", "Vertical")) {
            RadioMenuItem choice = new RadioMenuItem(mode);
            choice.setToggleGroup(layouts);
            choice.setSelected(mode.equals(settings.layoutMode()));
            choice.setOnAction(event -> {
                settings.layoutMode(mode);
                rebuildWorkspace();
            });
            layoutButton.getItems().add(choice);
        }
        layoutButton.getItems().add(new javafx.scene.control.SeparatorMenuItem());
        CheckMenuItem showFolders = new CheckMenuItem("Show folder pane");
        showFolders.setSelected(settings.showNavigation());
        showFolders.setOnAction(event -> {
            settings.showNavigation(showFolders.isSelected());
            rebuildWorkspace();
            status.setText(showFolders.isSelected() ? "Folder pane shown" : "Folder pane hidden");
        });
        CheckMenuItem showReader = new CheckMenuItem("Show reading pane");
        showReader.setSelected(settings.showReader());
        showReader.setOnAction(event -> {
            settings.showReader(showReader.isSelected());
            rebuildWorkspace();
            status.setText(showReader.isSelected() ? "Reading pane shown" : "Reading pane hidden");
        });
        CheckMenuItem compactNav = new CheckMenuItem("Compact navigation rows");
        compactNav.setSelected(settings.navCompact());
        compactNav.setOnAction(event -> {
            settings.navCompact(compactNav.isSelected());
            applyNavDensity();
            status.setText(compactNav.isSelected()
                    ? "Compact navigation on"
                    : "Compact navigation off");
        });
        layoutButton.getItems().addAll(showFolders, showReader, compactNav);

        Button settingsButton = new Button();
        settingsButton.setGraphic(ThunderbirdIcons.icon("settings", "toolbar-icon"));
        settingsButton.setAccessibleText("Settings");
        settingsButton.setTooltip(new javafx.scene.control.Tooltip("Settings"));
        settingsButton.getStyleClass().add("icon-button");
        settingsButton.setOnAction(event -> showSettings());

        MenuButton accountButton = new MenuButton();
        accountButton.setGraphic(ThunderbirdIcons.icon("account-settings", "toolbar-icon"));
        accountButton.setAccessibleText("Accounts and more");
        accountButton.setTooltip(new javafx.scene.control.Tooltip("Accounts and more"));
        MenuItem addAccountItem = new MenuItem("Add account…");
        addAccountItem.setOnAction(event -> addAccount());
        MenuItem diagnosticsItem = new MenuItem("Diagnostics…");
        diagnosticsItem.setOnAction(event -> showDiagnostics());
        MenuItem aboutItem = new MenuItem("About GraalMail");
        aboutItem.setOnAction(event -> showAbout());
        MenuItem shortcutsItem = new MenuItem("Keyboard shortcuts…");
        shortcutsItem.setOnAction(event -> showShortcuts());
        accountButton.getItems().addAll(addAccountItem,
                new javafx.scene.control.SeparatorMenuItem(),
                diagnosticsItem, shortcutsItem, aboutItem);

        ToolBar toolbar = new ToolBar(brand, navToggle, composeButton,
                searchBox, searchScope,
                refresh, layoutButton, settingsButton, accountButton);
        toolbar.getStyleClass().add("main-toolbar");
        toolbar.getStyleClass().add("proton-topbar");
        toolbar.setId("main-toolbar");
        return toolbar;
    }

    private BorderPane buildCenter() {
        folderTree.setShowRoot(false);
        folderTree.setCellFactory(view -> new FolderTreeCell(
                this::removeAccount, this::unreadTotal, this::markFolderRead));
        Label navSection = new Label("Mailboxes");
        navSection.getStyleClass().add("nav-section-label");
        HBox folderToolbar = new HBox(UiScale.px(owner, 3), navSection);
        javafx.scene.layout.Region folderSpacer = new javafx.scene.layout.Region();
        HBox.setHgrow(folderSpacer, Priority.ALWAYS);
        Button folderGetMessages = new Button();
        folderGetMessages.setGraphic(ThunderbirdIcons.icon("get-mail", "toolbar-icon"));
        folderGetMessages.setAccessibleText("Get messages for all accounts");
        folderGetMessages.setTooltip(new javafx.scene.control.Tooltip("Get Messages"));
        folderGetMessages.getStyleClass().add("icon-button");
        folderGetMessages.setOnAction(event -> syncNow());
        folderToolbar.getChildren().addAll(folderSpacer, folderGetMessages);
        folderToolbar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        folderToolbar.getStyleClass().add("folder-pane-toolbar");
        folderToolbar.getStyleClass().add("proton-nav-header");

        messageList.setCellFactory(view -> new MessageListCell(settings.compactDensity(), UiScale.scale(owner)));
        messageList.setPlaceholder(new Label("No messages yet — press Get messages to sync"));
        configureMessageTable();
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
        messageTable.setContextMenu(messageMenu);

        unreadFilter.getStyleClass().add("filter-chip");
        starredFilter.getStyleClass().add("filter-chip");
        attachmentsFilter.getStyleClass().add("filter-chip");
        unreadFilter.setOnAction(event -> { unreadOnly = unreadFilter.isSelected(); setActive(unreadFilter, unreadOnly); refreshFilterControls(); reloadCurrentView(); });
        starredFilter.setOnAction(event -> { flaggedOnly = starredFilter.isSelected(); setActive(starredFilter, flaggedOnly); refreshFilterControls(); reloadCurrentView(); });
        attachmentsFilter.setOnAction(event -> { attachmentsOnly = attachmentsFilter.isSelected(); setActive(attachmentsFilter, attachmentsOnly); refreshFilterControls(); reloadCurrentView(); });
        clearFiltersButton.setOnAction(event -> {
            unreadOnly = false;
            flaggedOnly = false;
            attachmentsOnly = false;
            unreadFilter.setSelected(false);
            starredFilter.setSelected(false);
            attachmentsFilter.setSelected(false);
            setActive(unreadFilter, false);
            setActive(starredFilter, false);
            setActive(attachmentsFilter, false);
            refreshFilterControls();
            reloadCurrentView();
        });
        clearFiltersButton.setAccessibleText("Clear all quick filters");
        clearFiltersButton.setTooltip(new javafx.scene.control.Tooltip("Clear filters"));
        clearFiltersButton.setVisible(false);
        clearFiltersButton.setManaged(false);
        javafx.scene.layout.FlowPane filters = new javafx.scene.layout.FlowPane(
                UiScale.px(owner, 6), UiScale.px(owner, 6),
                unreadFilter, starredFilter, attachmentsFilter, clearFiltersButton);
        filters.getStyleClass().add("quick-filter-bar");
        filters.setVisible(false);
        filters.setManaged(false);
        quickFilterToggle.setText("Filter");
        quickFilterToggle.setAccessibleText("Toggle quick filters");
        quickFilterToggle.setTooltip(new javafx.scene.control.Tooltip("Quick filters (Unread, Starred, Attachments)"));
        quickFilterToggle.setGraphic(ThunderbirdIcons.icon("filter", "toolbar-icon"));
        quickFilterToggle.getStyleClass().add("icon-button");
        quickFilterToggle.setOnAction(event -> {
            boolean show = !filters.isVisible();
            filters.setVisible(show);
            filters.setManaged(show);
        });
        listModePicker.getItems().setAll("Cards", "Table");
        listModePicker.setValue(settings.listMode());
        listModePicker.setAccessibleText("Message list display mode");
        listModePicker.setTooltip(new javafx.scene.control.Tooltip("Cards or table layout"));
        listModePicker.valueProperty().addListener((observable, old, value) -> {
            settings.listMode(value);
            updateListDisplayMode();
        });
        multiSelectButton.setAccessibleText("Toggle multiple message selection");
        multiSelectButton.setTooltip(new javafx.scene.control.Tooltip("Select multiple messages"));
        multiSelectButton.setGraphic(ThunderbirdIcons.icon("more", "toolbar-icon"));
        multiSelectButton.setOnAction(event -> {
            boolean multiple = messageList.getSelectionModel().getSelectionMode() == SelectionMode.SINGLE;
            messageList.getSelectionModel().setSelectionMode(multiple ? SelectionMode.MULTIPLE : SelectionMode.SINGLE);
            messageTable.getSelectionModel().setSelectionMode(multiple ? SelectionMode.MULTIPLE : SelectionMode.SINGLE);
            multiSelectButton.setText(multiple ? "Done" : "Select");
            if (multiple) multiSelectButton.getStyleClass().add("active");
            else multiSelectButton.getStyleClass().remove("active");
            if (!multiple) {
                var selected = messageList.getSelectionModel().getSelectedItem();
                messageList.getSelectionModel().clearSelection();
                if (selected != null) messageList.getSelectionModel().select(selected);
            }
            updateSelectionControls();
        });
        VBox titleText = new VBox(0, viewTitle, messageCount);
        titleText.getStyleClass().add("proton-list-titles");
        viewTitle.getStyleClass().add("proton-view-title");
        messageCount.getStyleClass().add("proton-message-count");
        javafx.scene.layout.Region titleSpacer = new javafx.scene.layout.Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        HBox titleRow = new HBox(UiScale.px(owner, 8),
                titleText, titleSpacer, quickFilterToggle, listModePicker, multiSelectButton);
        titleRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        titleRow.getStyleClass().add("list-title-row");
        titleRow.getStyleClass().add("proton-list-header");
        Button markSelectedRead = new Button("Read / unread");
        markSelectedRead.setGraphic(ThunderbirdIcons.icon("unread", "toolbar-icon"));
        markSelectedRead.setAccessibleText("Mark selected messages read or unread (U)");
        markSelectedRead.setTooltip(new javafx.scene.control.Tooltip("Mark read / unread (U)"));
        markSelectedRead.setOnAction(event -> toggleReadSelected());
        Button starSelected = new Button("Star / unstar");
        starSelected.setGraphic(ThunderbirdIcons.icon("star", "toolbar-icon"));
        starSelected.setAccessibleText("Star or unstar selected messages (S)");
        starSelected.setTooltip(new javafx.scene.control.Tooltip("Star / unstar (S)"));
        starSelected.setOnAction(event -> toggleStarSelected());
        Button archiveSelected = new Button("Archive");
        archiveSelected.setGraphic(ThunderbirdIcons.icon("archive", "toolbar-icon"));
        archiveSelected.setAccessibleText("Archive selected messages (E)");
        archiveSelected.setTooltip(new javafx.scene.control.Tooltip("Archive (E)"));
        archiveSelected.setOnAction(event -> archiveSelected());
        Button deleteSelected = new Button("Delete");
        deleteSelected.setGraphic(ThunderbirdIcons.icon("trash", "toolbar-icon"));
        deleteSelected.setAccessibleText("Delete selected messages (Del)");
        deleteSelected.setTooltip(new javafx.scene.control.Tooltip("Delete (Del)"));
        deleteSelected.setOnAction(event -> deleteSelected());
        javafx.scene.layout.FlowPane selectionActions = new javafx.scene.layout.FlowPane(
                UiScale.px(owner, 6), UiScale.px(owner, 6), selectionCount, markSelectedRead,
                starSelected, archiveSelected, deleteSelected);
        selectionActions.getStyleClass().add("selection-toolbar");
        selectionActions.setVisible(false);
        selectionActions.setManaged(false);
        loadMoreButton.setOnAction(event -> loadMoreMessages());
        loadMoreButton.setVisible(false);
        loadMoreButton.setManaged(false);
        loadMoreButton.setAccessibleText("Load the next page of messages");
        StackPane messageViews = new StackPane(messageList, messageTable);
        updateListDisplayMode();
        VBox listContent = new VBox(0, titleRow, selectionActions, filters, messageViews, loadMoreButton);
        listContent.getStyleClass().add("message-list-pane");
        VBox.setVgrow(messageViews, Priority.ALWAYS);
        listContent.setPadding(Insets.EMPTY);
        listPane = listContent;

        readerReplyButton.setText("Reply");
        readerReplyButton.setOnAction(event -> replyToSelected());
        readerForwardButton.setText("Forward");
        readerForwardButton.setOnAction(event -> forwardSelected());
        readerUnreadButton.setOnAction(event -> toggleReadSelected());
        readerStarButton.setOnAction(event -> toggleStarSelected());
        readerArchiveButton.setText("Archive");
        readerArchiveButton.setOnAction(event -> archiveSelected());
        readerJunkButton.setText("Junk");
        readerJunkButton.setOnAction(event -> junkSelected());
        readerDeleteButton.setText("Delete");
        readerDeleteButton.setOnAction(event -> deleteSelected());
        readerReplyButton.setGraphic(ThunderbirdIcons.icon("reply", "toolbar-icon"));
        readerForwardButton.setGraphic(ThunderbirdIcons.icon("forward", "toolbar-icon"));
        readerUnreadButton.setGraphic(ThunderbirdIcons.icon("unread", "toolbar-icon"));
        readerStarButton.setGraphic(ThunderbirdIcons.icon("star", "toolbar-icon"));
        readerJunkButton.setGraphic(ThunderbirdIcons.icon("spam", "toolbar-icon"));
        readerArchiveButton.setGraphic(ThunderbirdIcons.icon("archive", "toolbar-icon"));
        readerDeleteButton.setGraphic(ThunderbirdIcons.icon("trash", "toolbar-icon"));
        readerReplyButton.getStyleClass().add("proton-reply");
        moveButton.setOnAction(event -> showMoveMenu());
        moveButton.setGraphic(ThunderbirdIcons.icon("folder", "toolbar-icon"));
        previousButton.setText("Previous");
        nextButton.setText("Next");
        previousButton.setOnAction(event -> selectAdjacent(-1));
        nextButton.setOnAction(event -> selectAdjacent(1));
        remoteImagesButton.setOnAction(event -> {
            remoteImagesAllowed = !remoteImagesAllowed;
            if (currentMessage != null) reader.getEngine().loadContent(render(currentMessage, remoteImagesAllowed));
            remoteImagesButton.setText(remoteImagesAllowed ? "Block remote images" : "Show remote images for this message");
        });
        Button moreActions = new Button("More");
        moreActions.setGraphic(ThunderbirdIcons.icon("more", "toolbar-icon"));
        moreActions.getStyleClass().add("icon-button");
        ContextMenu readerMenu = new ContextMenu(
                new MenuItem("Mark read / unread"), new MenuItem("Star / unstar"),
                new MenuItem("Move to…"), new MenuItem("Junk"),
                new MenuItem("Previous message"), new MenuItem("Next message"));
        readerMenu.getItems().get(0).setOnAction(event -> toggleReadSelected());
        readerMenu.getItems().get(1).setOnAction(event -> toggleStarSelected());
        readerMenu.getItems().get(2).setOnAction(event -> showMoveMenu());
        readerMenu.getItems().get(3).setOnAction(event -> junkSelected());
        readerMenu.getItems().get(4).setOnAction(event -> selectAdjacent(-1));
        readerMenu.getItems().get(5).setOnAction(event -> selectAdjacent(1));
        moreActions.setOnAction(event -> readerMenu.show(moreActions,
                javafx.geometry.Side.BOTTOM, 0, 0));
        javafx.scene.layout.FlowPane readerBar = new javafx.scene.layout.FlowPane(
                UiScale.px(owner, 7), UiScale.px(owner, 7),
                readerReplyButton, readerForwardButton,
                readerUnreadButton, readerStarButton,
                previousButton, nextButton,
                readerArchiveButton, readerJunkButton, moveButton, readerDeleteButton, moreActions);
        readerBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        readerBar.setPadding(UiScale.insets(owner, 10));
        readerBar.getStyleClass().add("reader-actions");
        readerActionsPane = readerBar;
        readerReplyButton.setAccessibleText("Reply to selected message");
        readerReplyButton.setTooltip(new javafx.scene.control.Tooltip("Reply (R)"));
        readerForwardButton.setAccessibleText("Forward selected message");
        readerForwardButton.setTooltip(new javafx.scene.control.Tooltip("Forward (F)"));
        readerUnreadButton.setAccessibleText("Mark selected message read or unread");
        readerUnreadButton.setTooltip(new javafx.scene.control.Tooltip("Mark read / unread (U)"));
        readerStarButton.setAccessibleText("Star or unstar selected message");
        readerStarButton.setTooltip(new javafx.scene.control.Tooltip("Star / unstar (S)"));
        readerArchiveButton.setAccessibleText("Move selected message to Archive");
        readerArchiveButton.setTooltip(new javafx.scene.control.Tooltip("Archive (E)"));
        readerJunkButton.setAccessibleText("Move selected message to Junk");
        readerJunkButton.setTooltip(new javafx.scene.control.Tooltip("Move to Junk"));
        readerDeleteButton.setAccessibleText("Move selected message to Trash or delete");
        readerDeleteButton.setTooltip(new javafx.scene.control.Tooltip("Delete (Del)"));
        moveButton.setAccessibleText("Move selected message to another folder");
        moveButton.setTooltip(new javafx.scene.control.Tooltip("Move to…"));
        previousButton.setAccessibleText("Select previous message");
        previousButton.setTooltip(new javafx.scene.control.Tooltip("Previous message (K)"));
        nextButton.setAccessibleText("Select next message");
        nextButton.setTooltip(new javafx.scene.control.Tooltip("Next message (J)"));
        moreActions.setAccessibleText("More message actions");
        moreActions.setTooltip(new javafx.scene.control.Tooltip("More actions"));
        readerState.getStyleClass().add("reader-state");
        readerState.setPadding(UiScale.insets(owner, 0, 12, 6, 12));
        attachmentActions.setPadding(UiScale.insets(owner, 0, 10, 8, 10));
        remoteImagesButton.setVisible(false);
        remoteImagesButton.setManaged(false);
        remoteImagesButton.setAccessibleText("Allow remote images for this message only");
        readerHeader = buildReaderHeader();
        readerPane = new VBox(readerActionsPane, readerHeader, remoteImagesButton,
                readerState, reader);
        readerPane.getStyleClass().add("reader-pane");
        readerPane.getStyleClass().add("proton-reader-card");
        reader.setContextMenuEnabled(false);
        HBox.setHgrow(reader, Priority.ALWAYS);
        VBox.setVgrow(reader, Priority.ALWAYS);

        Button addAccountFooter = new Button("+  Add account");
        addAccountFooter.getStyleClass().add("proton-ghost-button");
        addAccountFooter.setMaxWidth(Double.MAX_VALUE);
        addAccountFooter.setOnAction(event -> addAccount());
        VBox navFooter = new VBox(addAccountFooter);
        navFooter.getStyleClass().add("proton-nav-footer");
        navigationPane = new VBox(folderToolbar, folderTree, navFooter);
        navigationPane.getStyleClass().add("navigation-pane");
        VBox.setVgrow(folderTree, Priority.ALWAYS);
        workspaceHost = new BorderPane();
        workspaceHost.getStyleClass().add("workspace-host");
        dividerSaveDelay.setOnFinished(event -> saveDividerPositions(workspaceHost.getCenter()));
        rebuildWorkspace();
        updateReaderControls(null);
        return workspaceHost;
    }

    private VBox buildReaderHeader() {
        readerSubject.getStyleClass().add("reader-subject");
        readerSubject.setWrapText(true);
        readerSender.getStyleClass().add("reader-sender");
        readerDate.getStyleClass().add("reader-date");
        readerMessageIndicators.getStyleClass().add("reader-indicators");
        readerRecipientSummary.getStyleClass().add("reader-recipients");
        readerRecipientSummary.setWrapText(true);
        readerRecipientDetails.getStyleClass().add("reader-recipient-details");
        readerRecipientDetails.setWrapText(true);
        readerDetailsToggle.setOnAction(event -> {
            boolean expanded = !readerRecipientDetails.isVisible();
            readerRecipientDetails.setVisible(expanded);
            readerRecipientDetails.setManaged(expanded);
            readerDetailsToggle.setText(expanded ? "Hide details" : "Show details");
        });
        readerDetailsToggle.setAccessibleText("Show or hide message recipient details");
        attachmentActions.getStyleClass().add("reader-attachments");
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox title = new HBox(UiScale.px(owner, 8), readerSubject, spacer, readerDate);
        title.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(readerSubject, Priority.ALWAYS);
        HBox.setHgrow(spacer, Priority.ALWAYS);
        VBox senderText = new VBox(1, readerSender, readerRecipientSummary);
        senderText.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(senderText, Priority.ALWAYS);
        HBox identity = new HBox(UiScale.px(owner, 10), readerAvatarHost, senderText, readerMessageIndicators);
        identity.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        identity.getStyleClass().add("reader-identity");
        VBox header = new VBox(UiScale.px(owner, 8), title, identity,
                readerDetailsToggle, readerRecipientDetails, attachmentActions);
        header.setPadding(UiScale.insets(owner, 10, 14, 10, 14));
        header.getStyleClass().add("reader-header");
        header.setVisible(false);
        header.setManaged(false);
        readerRecipientDetails.setVisible(false);
        readerRecipientDetails.setManaged(false);
        return header;
    }

    private void showReaderHeader(Message message) {
        readerSubject.setText(message.subject().isBlank() ? "(No subject)" : message.subject());
        String senderName = message.from().name().isBlank() ? message.from().email() : message.from().name();
        readerSender.setText(senderName);
        readerAvatarHost.getChildren().setAll(
                ProtonAvatar.avatar(message.from().name(), message.from().email(), 40 * UiScale.currentScale()));
        readerDate.setText(formatMessageDate(message.date()));
        List<Address> recipients = message.to();
        readerRecipientSummary.setText("To: " + (recipients.isEmpty()
                ? "(no recipients listed)" : formatAddresses(recipients)));
        readerRecipientDetails.setText("To: " + formatAddresses(message.to())
                + "\nCc: " + formatAddresses(message.cc())
                + "\nBcc: " + formatAddresses(message.bcc()));
        List<String> indicators = new ArrayList<>();
        if (message.flagged()) indicators.add("Starred");
        if (message.hasAttachments()) indicators.add(message.attachments().size() == 1
                ? "1 attachment" : message.attachments().size() + " attachments");
        readerMessageIndicators.setText(String.join(" · ", indicators));
        readerDetailsToggle.setText("Show details");
        readerRecipientDetails.setVisible(false);
        readerRecipientDetails.setManaged(false);
        readerHeader.setVisible(true);
        readerHeader.setManaged(true);
    }

    private static String formatAddresses(List<Address> addresses) {
        return addresses.stream().map(Address::format).collect(java.util.stream.Collectors.joining(", "));
    }

    private static String formatMessageDate(Instant date) {
        return date == null ? "" : DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                .withLocale(java.util.Locale.getDefault()).withZone(ZoneId.systemDefault()).format(date);
    }

    private void configureMessageTable() {
        messageTable.setPlaceholder(new Label("No messages yet — press Get messages to sync"));
        messageTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<Message, String> state = new TableColumn<>("Status");
        state.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().unread() ? "Unread" : ""));
        state.setCellFactory(column -> new javafx.scene.control.TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                setGraphic(empty || item == null || item.isBlank() ? null : unreadStatusIcon());
                setAccessibleText(empty || item == null || item.isBlank() ? null : "Unread");
            }
        });
        TableColumn<Message, String> star = new TableColumn<>("Star");
        star.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().flagged() ? "★" : ""));
        star.setCellFactory(column -> new javafx.scene.control.TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                setGraphic(empty || item == null || item.isBlank()
                        ? null : ThunderbirdIcons.icon("star", "message-star-icon"));
                setAccessibleText(empty || item == null || item.isBlank() ? null : "Starred");
            }
        });
        TableColumn<Message, String> sender = new TableColumn<>("Sender");
        sender.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().from().name().isBlank()
                ? cell.getValue().from().email() : cell.getValue().from().name()));
        TableColumn<Message, String> subject = new TableColumn<>("Subject");
        subject.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().subject().isBlank()
                ? "(No subject)" : cell.getValue().subject()));
        TableColumn<Message, Instant> date = new TableColumn<>("Date");
        date.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue().date()));
        date.setCellFactory(column -> new javafx.scene.control.TableCell<>() {
            @Override protected void updateItem(Instant item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : DateTimeFormatter
                        .ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(java.util.Locale.getDefault())
                        .withZone(ZoneId.systemDefault()).format(item));
            }
        });
        TableColumn<Message, String> attachment = new TableColumn<>("Attachment");
        attachment.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue().hasAttachments() ? "Yes" : ""));
        attachment.setCellFactory(column -> new javafx.scene.control.TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                setGraphic(empty || item == null || item.isBlank()
                        ? null : ThunderbirdIcons.icon("attachment", "message-attachment-icon"));
                setAccessibleText(empty || item == null || item.isBlank() ? null : "Has attachment");
            }
        });
        TableColumn<Message, Number> size = new TableColumn<>("Size");
        size.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue().size()));
        size.setCellFactory(column -> new javafx.scene.control.TableCell<>() {
            @Override protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(""); return; }
                long bytes = item.longValue();
                String text;
                if (bytes < 1024) text = bytes + " B";
                else if (bytes < 1024 * 1024) text = String.format("%.1f KB", bytes / 1024.0);
                else text = String.format("%.1f MB", bytes / (1024.0 * 1024.0));
                setText(text);
            }
        });
        state.prefWidthProperty().bind(messageTable.widthProperty().multiply(0.07));
        star.prefWidthProperty().bind(messageTable.widthProperty().multiply(0.05));
        sender.prefWidthProperty().bind(messageTable.widthProperty().multiply(0.18));
        subject.prefWidthProperty().bind(messageTable.widthProperty().multiply(0.30));
        date.prefWidthProperty().bind(messageTable.widthProperty().multiply(0.18));
        size.prefWidthProperty().bind(messageTable.widthProperty().multiply(0.10));
        attachment.prefWidthProperty().bind(messageTable.widthProperty().multiply(0.12));
        messageTable.getColumns().setAll(state, star, sender, subject, date, size, attachment);
        messageTable.setRowFactory(table -> new javafx.scene.control.TableRow<>() {
            @Override protected void updateItem(Message item, boolean empty) {
                super.updateItem(item, empty);
                setAccessibleText(empty || item == null ? null :
                        (item.unread() ? "Unread. " : "") + item.from().email() + ". "
                                + (item.subject().isBlank() ? "No subject" : item.subject())
                                + (item.hasAttachments() ? ". Has attachment." : ""));
            }
        });
    }

    private Node unreadStatusIcon() {
        javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(UiScale.px(owner, 3));
        dot.getStyleClass().add("message-unread-dot");
        return dot;
    }

    private void updateListDisplayMode() {
        boolean table = "Table".equals(settings.listMode());
        messageList.setVisible(!table);
        messageList.setManaged(!table);
        messageTable.setVisible(table);
        messageTable.setManaged(table);
    }

    private void synchronizeSelection(ListView<Message> source, TableView<Message> target) {
        synchronizingSelection = true;
        try {
            target.getSelectionModel().clearSelection();
            for (Message selected : source.getSelectionModel().getSelectedItems())
                target.getSelectionModel().select(selected);
        } finally {
            synchronizingSelection = false;
        }
    }

    private void synchronizeSelection(TableView<Message> source, ListView<Message> target) {
        synchronizingSelection = true;
        try {
            target.getSelectionModel().clearSelection();
            for (Message selected : source.getSelectionModel().getSelectedItems())
                target.getSelectionModel().select(selected);
        } finally {
            synchronizingSelection = false;
        }
    }

    private void rebuildWorkspace() {
        if (workspaceHost == null) return;
        boolean showNavigation = settings.showNavigation();
        boolean showReader = settings.showReader();
        String mode = settings.layoutMode();
        Node root;
        mode = effectiveLayoutMode();
        activeLayoutMode = mode;
        if ("Classic".equals(mode)) {
            SplitPane reading = split(Orientation.VERTICAL,
                    showReader ? List.of(listPane, readerPane) : List.of(listPane));
            root = showNavigation
                    ? split(Orientation.HORIZONTAL, List.of(navigationPane, reading))
                    : reading;
        } else if ("Wide".equals(mode)) {
            SplitPane top = split(Orientation.HORIZONTAL,
                    showNavigation ? List.of(navigationPane, listPane) : List.of(listPane));
            root = showReader
                    ? split(Orientation.VERTICAL, List.of(top, readerPane)) : top;
        } else {
            List<Node> panes = new ArrayList<>();
            if (showNavigation) panes.add(navigationPane);
            panes.add(listPane);
            if (showReader) panes.add(readerPane);
            root = split(Orientation.HORIZONTAL, panes);
        }
        workspaceHost.setCenter(root);
        root.getStyleClass().add("mail-split-pane");
        applyNavDensity();
    }

    /** Applies the persisted navigation density to the folder pane. */
    private void applyNavDensity() {
        if (navigationPane == null) return;
        if (settings.navCompact()) {
            if (!navigationPane.getStyleClass().contains("nav-compact")) {
                navigationPane.getStyleClass().add("nav-compact");
            }
        } else {
            navigationPane.getStyleClass().remove("nav-compact");
        }
        folderTree.refresh();
    }

    private SplitPane split(Orientation orientation, List<Node> children) {
        SplitPane pane = new SplitPane();
        pane.setOrientation(orientation);
        pane.getItems().setAll(children);
        pane.setStyle("-fx-background-color: transparent;");
        if (children.size() > 1) {
            String mode = activeLayoutMode == null ? settings.layoutMode() : activeLayoutMode;
            int index = "Vertical".equals(mode) ? (orientation == Orientation.HORIZONTAL ? 0 : 2)
                    : "Classic".equals(mode) ? (orientation == Orientation.HORIZONTAL ? 0 : 1)
                    : (orientation == Orientation.HORIZONTAL ? 1 : 2);
            pane.setDividerPositions(settings.paneDivider(mode, index, orientation == Orientation.VERTICAL
                    ? 0.62 : 0.30));
            pane.getDividers().forEach(divider -> divider.positionProperty().addListener(
                    (observable, old, value) -> dividerSaveDelay.playFromStart()));
        }
        return pane;
    }

    private void saveDividerPositions(Node node) {
        int[] index = {0};
        saveDividerPositions(node, index);
    }

    private void saveDividerPositions(Node node, int[] index) {
        if (node instanceof SplitPane pane) {
            String mode = activeLayoutMode == null ? settings.layoutMode() : activeLayoutMode;
            int dividerIndex = "Vertical".equals(mode) ? 0
                    : "Classic".equals(mode)
                    ? (pane.getOrientation() == Orientation.HORIZONTAL ? 0 : 1)
                    : (pane.getOrientation() == Orientation.HORIZONTAL ? 1 : 2);
            for (double position : pane.getDividerPositions()) {
                settings.setPaneDivider(mode, dividerIndex++, position);
            }
            pane.getItems().forEach(child -> saveDividerPositions(child, index));
        }
    }

    private boolean isDarkTheme() {
        return settings.darkTheme();
    }

    private static final double NARROW_LAYOUT_WIDTH = 1120;

    private String effectiveLayoutMode() {
        String configured = settings.layoutMode();
        if (mainScene != null && mainScene.getWidth() > 0
                && mainScene.getWidth() < NARROW_LAYOUT_WIDTH
                && "Vertical".equals(configured)) {
            return "Classic";
        }
        return configured;
    }

    private void refreshScaledContent() {
        double scale = UiScale.scale(owner);
        reader.setZoom(scale);
        messageList.setCellFactory(view -> new MessageListCell(settings.compactDensity(), scale));
        folderTree.refresh();
        if (currentMessage != null && readerHeader != null && readerHeader.isVisible()) {
            readerAvatarHost.getChildren().setAll(ProtonAvatar.avatar(
                    currentMessage.from().name(), currentMessage.from().email(),
                    40 * UiScale.currentScale()));
        }
    }

    private HBox buildStatusBar() {
        Label connection = new Label("Local cache");
        connection.getStyleClass().add("connection-indicator");
        connection.setAccessibleText("Connection: local cache");
        polyMailStatus.getStyleClass().add("polymail-indicator");
        polyMailStatus.setAccessibleText("PolyMail network status");
        status.setAccessibleText("Status messages");
        status.setAccessibleHelp("Announces what the application just did");
        HBox box = new HBox(UiScale.px(owner, 10), connection, status, messageCountLabel, polyMailStatus);
        HBox.setHgrow(status, Priority.ALWAYS);
        box.setPadding(UiScale.insets(owner, 7, 12, 7, 12));
        box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        box.getStyleClass().add("status-bar");
        return box;
    }

    private void updateMessageCount() {
        int count = messages.size();
        int unread = 0;
        for (Message m : messages) if (m.unread()) unread++;
        messageCountLabel.setText(count == 0 ? "" : count + " message(s), " + unread + " unread");
    }

    private void refreshFolders() {
        TreeItem<Object> root = folderTree.getRoot();
        if (root == null) {
            root = new TreeItem<>("Mailboxes");
            folderTree.setRoot(root);
        }
        Object selectedValue = folderTree.getSelectionModel().getSelectedItem() == null
                ? null : folderTree.getSelectionModel().getSelectedItem().getValue();
        java.util.Set<String> expandedAccounts = new java.util.HashSet<>();
        java.util.Set<String> existingAccounts = new java.util.HashSet<>();
        for (TreeItem<Object> accountItem : root.getChildren()) {
            if (accountItem.getValue() instanceof Account account) {
                existingAccounts.add(account.id());
                if (accountItem.isExpanded()) expandedAccounts.add(account.id());
            }
        }
        List<TreeItem<Object>> items = buildTreeItems();
        root.getChildren().setAll(items);
        root.setExpanded(true);
        items.stream().filter(item -> item.getValue() instanceof Account)
                .forEach(item -> {
                    String id = ((Account) item.getValue()).id();
                    item.setExpanded(!existingAccounts.contains(id) || expandedAccounts.contains(id));
                });
        if (!items.isEmpty()) {
            TreeItem<Object> target = findTreeItem(items, selectedValue);
            if (target == null && !settings.lastFolderId().isBlank()) {
                String lastFolder = settings.lastFolderId();
                target = items.stream().flatMap(account -> account.getChildren().stream())
                        .filter(item -> item.getValue() instanceof Folder folder
                                && folder.id().equals(lastFolder)).findFirst().orElse(null);
            }
            if (target == null && !settings.lastAccountId().isBlank()) {
                String lastId = settings.lastAccountId();
                target = items.stream().filter(entry -> entry.getValue() instanceof Account account
                        && account.id().equals(lastId)).findFirst().orElse(null);
            }
            if (target == null) target = items.getFirst();
            folderTree.getSelectionModel().select(target);
        }
    }

    private int unreadTotal(Account account) {
        try {
            int total = 0;
            for (Folder folder : context.store().listFolders(account.id())) {
                total += Math.max(0, folder.unreadCount());
            }
            return total;
        } catch (RuntimeException e) {
            LOG.debug("Unread rollup unavailable for account {}", account.id());
            return 0;
        }
    }

    /** Adjusts the cached unread count live; the next sync corrects any drift. */
    private void adjustFolderUnread(String accountId, String folderId, int delta) {
        if (delta == 0 || accountId == null || folderId == null) return;
        try {
            List<Folder> folders = new ArrayList<>(context.store().listFolders(accountId));
            boolean changed = false;
            for (int i = 0; i < folders.size(); i++) {
                Folder folder = folders.get(i);
                if (folder.id().equals(folderId)) {
                    folders.set(i, folder.withCounts(folder.totalCount(),
                            Math.max(0, folder.unreadCount() + delta)));
                    changed = true;
                    break;
                }
            }
            if (changed) context.store().saveFolders(accountId, folders);
        } catch (RuntimeException e) {
            LOG.debug("Live unread adjustment failed for account {}", accountId);
            return;
        }
        updateFolderTreeItem(accountId, folderId);
    }

    /** Sets the cached unread count live; the next sync corrects any drift. */
    private void setFolderUnread(String accountId, String folderId, int unread) {
        if (accountId == null || folderId == null) return;
        try {
            List<Folder> folders = new ArrayList<>(context.store().listFolders(accountId));
            boolean changed = false;
            for (int i = 0; i < folders.size(); i++) {
                Folder folder = folders.get(i);
                if (folder.id().equals(folderId)) {
                    folders.set(i, folder.withCounts(folder.totalCount(), Math.max(0, unread)));
                    changed = true;
                    break;
                }
            }
            if (changed) context.store().saveFolders(accountId, folders);
        } catch (RuntimeException e) {
            LOG.debug("Live unread update failed for account {}", accountId);
            return;
        }
        updateFolderTreeItem(accountId, folderId);
    }

    private void updateFolderTreeItem(String accountId, String folderId) {
        if (folderTree.getRoot() == null) return;
        for (TreeItem<Object> accountItem : folderTree.getRoot().getChildren()) {
            if (accountItem.getValue() instanceof Account account
                    && account.id().equals(accountId)) {
                for (TreeItem<Object> folderItem : accountItem.getChildren()) {
                    if (folderItem.getValue() instanceof Folder folder
                            && folder.id().equals(folderId)) {
                        context.store().folder(accountId, folderId)
                                .ifPresent(folderItem::setValue);
                    }
                }
            }
        }
        folderTree.refresh();
    }

    /** Decrements cached unread counts for messages leaving their folder. */
    private void noteMessagesRemoved(java.util.Collection<Message> removed) {
        java.util.Map<String, Integer> deltas = new java.util.HashMap<>();
        for (Message message : removed) {
            if (message.unread()) {
                deltas.merge(message.accountId() + "\u0000" + message.folderId(), 1, Integer::sum);
            }
        }
        deltas.forEach((key, count) -> {
            int split = key.indexOf('\u0000');
            adjustFolderUnread(key.substring(0, split), key.substring(split + 1), -count);
        });
    }

    /** Queues MARK_READ for every cached unread message in the folder. */
    private void markFolderRead(Account account, Folder folder) {
        status.setText("Marking " + folder.name() + " as read…");
        io.submit(() -> {
            try {
                int marked = 0;
                int offset = 0;
                while (marked < 2000) {
                    var page = context.store().listMessages(account.id(), folder.id(), 200, offset);
                    if (page.messages().isEmpty()) break;
                    boolean advanced = false;
                    for (Message message : page.messages()) {
                        offset++;
                        if (message.unread()) {
                            context.syncEngine().queue(account, PendingAction.Type.MARK_READ,
                                    message.id(), "");
                            updateVisibleMessageQuiet(message.toBuilder().unread(false).build());
                            marked++;
                            advanced = true;
                        }
                    }
                    if (!page.hasMore()) break;
                    if (!advanced && page.messages().size() < 200) break;
                }
                int done = marked;
                adjustFolderUnread(account.id(), folder.id(), -done);
                Platform.runLater(() -> {
                    status.setText(done == 0 ? "Nothing unread in " + folder.name()
                            : "Marked " + done + " message(s) as read — change queued for synchronisation");
                    refreshFolders();
                    reloadCurrentView();
                });
            } catch (RuntimeException e) {
                LOG.warn("Mark all as read failed: {}", e.getMessage());
                Platform.runLater(() -> status.setText("Could not mark as read: " + e.getMessage()));
            }
        });
    }

    /** Updates the visible row without changing the selection. */
    private void updateVisibleMessageQuiet(Message updated) {
        Platform.runLater(() -> {
            int index = messages.indexOf(messageList.getSelectionModel().getSelectedItem());
            if (index < 0) {
                for (int i = 0; i < messages.size(); i++) {
                    if (messages.get(i).id().equals(updated.id())) {
                        index = i;
                        break;
                    }
                }
            }
            if (index >= 0) messages.set(index, updated);
        });
    }

    private TreeItem<Object> findTreeItem(List<TreeItem<Object>> items, Object value) {
        if (value == UNIFIED_INBOX) return items.stream()
                .filter(item -> item.getValue() == UNIFIED_INBOX).findFirst().orElse(null);
        if (value instanceof Account account) return items.stream()
                .filter(item -> item.getValue() instanceof Account candidate
                        && candidate.id().equals(account.id())).findFirst().orElse(null);
        if (value instanceof Folder folder) return items.stream()
                .flatMap(item -> item.getChildren().stream())
                .filter(item -> item.getValue() instanceof Folder candidate
                        && candidate.id().equals(folder.id())).findFirst().orElse(null);
        return null;
    }

    private void selectFolderInTree(Account account, Folder folder) {
        if (folderTree.getRoot() == null || folder == null) {
            return;
        }
        for (TreeItem<Object> accountItem : folderTree.getRoot().getChildren()) {
            if (accountItem.getValue() instanceof Account candidate
                    && candidate.id().equals(account.id())) {
                accountItem.setExpanded(true);
                for (TreeItem<Object> folderItem : accountItem.getChildren()) {
                    if (folderItem.getValue() instanceof Folder candidateFolder
                            && candidateFolder.id().equals(folder.id())) {
                        folderTree.getSelectionModel().select(folderItem);
                        folderTree.scrollTo(folderTree.getRow(folderItem));
                        return;
                    }
                }
            }
        }
    }

    private List<TreeItem<Object>> buildTreeItems() {
        List<TreeItem<Object>> items = new java.util.ArrayList<>();
        TreeItem<Object> unified = new TreeItem<>(UNIFIED_INBOX);
        items.add(unified);
        for (Account account : context.store().listAccounts()) {
            TreeItem<Object> accountItem = new TreeItem<>(account);
            List<Folder> folders = new ArrayList<>(context.store().listFolders(account.id()));
            folders.sort(java.util.Comparator.comparingInt((Folder folder) -> folderOrder(folder.type()))
                    .thenComparing(folder -> folder.name().toLowerCase(java.util.Locale.ROOT)));
            for (Folder folder : folders) {
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

    private static int folderOrder(com.graalmail.core.domain.FolderType type) {
        return switch (type) {
            case INBOX -> 0;
            case DRAFTS -> 1;
            case SENT -> 2;
            case ARCHIVE -> 3;
            case SPAM -> 4;
            case TRASH -> 5;
            case OUTBOX -> 6;
            case ALL -> 7;
            case UNIFIED_INBOX -> 8;
            case CUSTOM -> 9;
        };
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
            Folder preferred = folders.stream()
                    .filter(folder -> folder.id().equals(settings.lastFolderId()))
                    .findFirst().orElse(null);
            final String preferredId = preferred == null ? null : preferred.id();
            boolean preferredBelongsToAccount = preferredId != null
                    && folders.stream().anyMatch(folder -> folder.id().equals(preferredId));
            if (!preferredBelongsToAccount) {
                preferred = folders.stream()
                        .filter(folder -> folder.type() == com.graalmail.core.domain.FolderType.INBOX)
                        .findFirst().orElse(folders.isEmpty() ? null : folders.getFirst());
            }
            selectedFolder.set(preferred);
            messages.clear();
            status.setText(account.email());
            viewTitle.setText(account.displayName().isBlank() ? account.email() : account.displayName());
            settings.lastAccountId(account.id());
            if (selectedFolder.get() != null) {
                settings.lastFolderId(selectedFolder.get().id());
                loadMessages(account.id(), selectedFolder.get().id());
            }
            selectFolderInTree(account, selectedFolder.get());
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
        } else {
            messages.clear();
            messagesChanged("messages");
            viewTitle.setText("GraalMail");
            status.setText("Add an account to get started.");
            onMessageSelected(null);
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
                    messagesChanged("messages");
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
                    messagesChanged("messages");
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
                    messagesChanged("messages");
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
        int generation = ++readerGeneration;
        currentMessage = message;
        updateReaderControls(message);
        attachmentActions.getChildren().clear();
        if (message == null) {
            setReaderState("");
            readerHeader.setVisible(false);
            readerHeader.setManaged(false);
            remoteImagesAllowed = false;
            remoteImagesButton.setVisible(false);
            remoteImagesButton.setManaged(false);
            reader.getEngine().loadContent(readerEmptyHtml(
                    "Nothing selected",
                    context.store().listAccounts().isEmpty()
                            ? "Add an account with + Add account, then press Get messages."
                            : "Select a message on the left to read it here."));
            return;
        }
        remoteImagesAllowed = false;
        showReaderHeader(message);
        readerState.setText("Loading cached message…");
        remoteImagesButton.setVisible(false);
        remoteImagesButton.setManaged(false);
        reader.getEngine().loadContent(readerEmptyHtml("Loading…", "Fetching the message from the local cache."));
        io.submit(() -> {
            Message full;
            String pending;
            try {
                full = context.store().message(message.id()).orElse(message);
                pending = pendingActionStatus(full);
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    if (generation != readerGeneration) return;
                    readerState.setText("Could not load this cached message: " + e.getMessage());
                    reader.getEngine().loadContent("<html><body><p>The message could not be loaded. "
                            + "It remains in the message list; select it to retry.</p></body></html>");
                });
                return;
            }
            Message loaded = full;
            String pendingState = pending;
            Platform.runLater(() -> {
                Message selected = messageList.getSelectionModel().getSelectedItem();
                if (generation != readerGeneration || selected == null
                        || !selected.id().equals(message.id())) return;
                Message displayMessage = loaded;
                if (loaded.unread()) {
                    context.store().account(loaded.accountId()).ifPresent(account ->
                            context.syncEngine().queue(account, PendingAction.Type.MARK_READ, loaded.id(), ""));
                    adjustFolderUnread(loaded.accountId(), loaded.folderId(), -1);
                    displayMessage = loaded.toBuilder().unread(false).build();
                    int index = messages.indexOf(selected);
                    if (index >= 0) {
                        synchronizingSelection = true;
                        try {
                            messages.set(index, displayMessage);
                            messageList.getSelectionModel().select(displayMessage);
                            messageTable.getSelectionModel().select(displayMessage);
                        } finally {
                            synchronizingSelection = false;
                        }
                    }
                }
                currentMessage = displayMessage;
                showReaderHeader(displayMessage);
                setReaderState(pendingState);
                boolean remoteImages = containsRemoteImages(displayMessage.bodyHtml());
                remoteImagesButton.setText("Show remote images for this message");
                remoteImagesButton.setVisible(remoteImages);
                remoteImagesButton.setManaged(remoteImages);
                reader.getEngine().loadContent(render(displayMessage, false));
                showAttachments(displayMessage);
            });
        });
    }

    private void setReaderState(String text) {
        boolean empty = text == null || text.isBlank();
        readerState.setText(empty ? "" : text);
        readerState.setVisible(!empty);
        readerState.setManaged(!empty);
    }

    private String render(Message message) {
        return MessageBodyRenderer.render(message, false, isDarkTheme());
    }

    private String render(Message message, boolean allowRemoteImages) {
        return MessageBodyRenderer.render(message, allowRemoteImages, isDarkTheme());
    }

    private String readerEmptyHtml(String title, String body) {
        boolean dark = isDarkTheme();
        String bg = dark ? "#2A2E32" : "#FCFCFC";
        String fg = dark ? "#FCFCFC" : "#232629";
        String muted = dark ? "#95A5A6" : "#7F7F7F";
        return "<html><body style='background:" + bg + ";color:" + fg
                + ";font-family:sans-serif;padding:32px;text-align:center;'>"
                + "<h2 style='font-size:15px;margin:0 0 8px;'>" + title + "</h2>"
                + "<p style='font-size:13px;color:" + muted + ";margin:0;'>" + body + "</p>"
                + "</body></html>";
    }

    private String pendingActionStatus(Message message) {
        return context.store().pendingActions(message.accountId()).stream()
                .filter(action -> action.targetId().equals(message.id()))
                .map(action -> action.state() == PendingAction.State.FAILED
                        ? "A mail action failed: " + action.lastError()
                        : "This change is waiting to synchronise")
                .findFirst().orElse("");
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
            Button download = new Button("Save  " + attachment.fileName() + "  ("
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
        if (readerActionsPane != null) {
            readerActionsPane.setVisible(selected);
            readerActionsPane.setManaged(selected);
        }
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
        previousButton.setDisable(!selected || selectedIndex() <= 0);
        nextButton.setDisable(!selected || selectedIndex() < 0
                || selectedIndex() >= messages.size() - 1);
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
        List<Message> selected = selectedMessages();
        if (selected.isEmpty()) return;
        List<Message> queued = new ArrayList<>();
        int unavailable = 0;
        for (Message message : selected) {
            Account account = context.store().account(message.accountId()).orElse(null);
            Folder archive = account == null ? null : context.store().listFolders(account.id()).stream()
                    .filter(folder -> folder.type() == com.graalmail.core.domain.FolderType.ARCHIVE)
                    .findFirst().orElse(null);
            if (archive == null) {
                unavailable++;
                continue;
            }
            context.syncEngine().queue(account, PendingAction.Type.MOVE, message.id(), archive.id());
            queued.add(message);
        }
        noteMessagesRemoved(queued);
        messages.removeAll(queued);
        onMessageSelected(messageList.getSelectionModel().getSelectedItem());
        status.setText(queued.size() + " Archive action(s) queued for synchronisation"
                + (unavailable > 0 ? "; " + unavailable + " account(s) have no Archive folder" : ""));
    }

    private void junkSelected() {
        List<Message> selected = selectedMessages();
        if (selected.isEmpty()) return;
        List<Message> queued = new ArrayList<>();
        int unavailable = 0;
        for (Message message : selected) {
            Account account = context.store().account(message.accountId()).orElse(null);
            Folder spam = account == null ? null : context.store().listFolders(account.id()).stream()
                    .filter(folder -> folder.type() == com.graalmail.core.domain.FolderType.SPAM)
                    .findFirst().orElse(null);
            if (spam == null) {
                unavailable++;
                continue;
            }
            context.syncEngine().queue(account, PendingAction.Type.MOVE, message.id(), spam.id());
            queued.add(message);
        }
        noteMessagesRemoved(queued);
        messages.removeAll(queued);
        onMessageSelected(messageList.getSelectionModel().getSelectedItem());
        status.setText(queued.size() + " Junk action(s) queued for synchronisation"
                + (unavailable > 0 ? "; " + unavailable + " account(s) have no Junk folder" : ""));
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
        if (selected != null) noteMessagesRemoved(List.of(selected));
        messagesChanged("messages");
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
                    messagesChanged("results");
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

    private void setActive(ToggleButton button, boolean active) {
        if (active) button.getStyleClass().add("active");
        else button.getStyleClass().remove("active");
    }

    private void refreshFilterControls() {
        boolean active = unreadOnly || flaggedOnly || attachmentsOnly;
        clearFiltersButton.setVisible(active);
        clearFiltersButton.setManaged(active);
        quickFilterToggle.setText(active ? "Filter •" : "Filter");
    }

    /** Refreshes chip counts, result count and context-aware empty states. */
    private void messagesChanged(String scope) {
        long unread = messages.stream().filter(Message::unread).count();
        long starred = messages.stream().filter(Message::flagged).count();
        long withFiles = messages.stream().filter(Message::hasAttachments).count();
        unreadFilter.setText(unread > 0 ? "Unread (" + unread + ")" : "Unread");
        starredFilter.setText(starred > 0 ? "Starred (" + starred + ")" : "Starred");
        attachmentsFilter.setText(withFiles > 0 ? "Attachments (" + withFiles + ")" : "Attachments");
        if ("results".equals(scope)) {
            messageCount.setText(messages.size() + " results");
        } else {
            messageCount.setText(messages.size() + " messages");
        }
        updatePlaceholders();
    }

    private void updatePlaceholders() {
        boolean searching = !searchText.get().isBlank();
        boolean filtered = unreadOnly || flaggedOnly || attachmentsOnly;
        boolean noAccounts = context.store().listAccounts().isEmpty();
        String text;
        if (noAccounts) {
            text = "No accounts yet — use + Add account below to get started";
        } else if (searching) {
            text = "No matches for “" + searchText.get().trim() + "” — clear search or filters";
        } else if (filtered) {
            text = "No messages match the active filters — press Clear filters";
        } else if (selectedFolder.get() == null && !unifiedInboxSelected && selectedAccount.get() == null) {
            text = "Select a mailbox on the left to read mail";
        } else {
            text = "This folder is empty — press Get messages to sync";
        }
        messageList.setPlaceholder(new Label(text));
        messageTable.setPlaceholder(new Label(text));
    }

    private void updateSelectionControls() {
        boolean tableMode = "Table".equalsIgnoreCase(settings.listMode());
        int count = tableMode
                ? messageTable.getSelectionModel().getSelectedItems().size()
                : messageList.getSelectionModel().getSelectedItems().size();
        boolean multi = (tableMode
                ? messageTable.getSelectionModel().getSelectionMode()
                : messageList.getSelectionModel().getSelectionMode()) == SelectionMode.MULTIPLE;
        selectionCount.setText(count + (count == 1 ? " selected" : " selected"));
        boolean visible = count > 1 || (multi && count > 0);
        selectionCount.getParent().setVisible(visible);
        selectionCount.getParent().setManaged(visible);
    }

    private void toggleReadSelected() {
        List<Message> selected = selectedMessages();
        if (selected.isEmpty()) return;
        for (Message message : selected) {
            Account account = context.store().account(message.accountId()).orElse(null);
            if (account == null) continue;
            PendingAction.Type type = message.unread() ? PendingAction.Type.MARK_READ : PendingAction.Type.MARK_UNREAD;
            context.syncEngine().queue(account, type, message.id(), "");
            adjustFolderUnread(message.accountId(), message.folderId(), message.unread() ? -1 : 1);
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

    private int selectedIndex() {
        boolean tableMode = "Table".equalsIgnoreCase(settings.listMode());
        return tableMode
                ? messageTable.getSelectionModel().getSelectedIndex()
                : messageList.getSelectionModel().getSelectedIndex();
    }

    private List<Message> selectedMessages() {
        boolean tableMode = "Table".equalsIgnoreCase(settings.listMode());
        List<Message> selected = new ArrayList<>(tableMode
                ? messageTable.getSelectionModel().getSelectedItems()
                : messageList.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            Message current = tableMode
                    ? messageTable.getSelectionModel().getSelectedItem()
                    : messageList.getSelectionModel().getSelectedItem();
            if (current != null) {
                selected.add(current);
            } else {
                Message fallback = !tableMode
                        ? messageTable.getSelectionModel().getSelectedItem()
                        : messageList.getSelectionModel().getSelectedItem();
                if (fallback != null) {
                    selected.add(fallback);
                }
            }
        }
        return selected;
    }

    private void deleteSelected() {
        List<Message> selected = selectedMessages();
        if (selected.isEmpty()) return;
        boolean permanent = selected.stream().anyMatch(message -> context.store().listFolders(message.accountId()).stream()
                .noneMatch(folder -> folder.type() == com.graalmail.core.domain.FolderType.TRASH));
        if (permanent) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "At least one selected account has no Trash folder. Delete those messages permanently?");
            confirm.setTitle("Delete message");
            confirm.setHeaderText(null);
            boolean maximized = UiScale.isMaximized(owner);
            var choice = confirm.showAndWait();
            UiScale.restoreMaximized(owner, maximized);
            if (choice.filter(button -> button == javafx.scene.control.ButtonType.OK).isEmpty()) return;
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
        noteMessagesRemoved(selected);
        messagesChanged("messages");
        onMessageSelected(null);
        status.setText(selected.size() == 1
                ? (movedToTrash == 1 ? "Message moved to Trash; change queued for synchronisation"
                : "Message deletion queued for synchronisation")
                : selected.size() + " message actions queued for synchronisation");
    }

    private void updateVisibleMessage(Message updated) {
        int index = messages.indexOf(messageList.getSelectionModel().getSelectedItem());
        if (index >= 0) messages.set(index, updated);
        messagesChanged("messages");
        onMessageSelected(updated);
    }

    public void close() {
        searchDelay.stop();
        dividerSaveDelay.stop();
        notifier.dispose();
        if (polyMail != null) {
            try {
                polyMail.close();
            } catch (RuntimeException e) {
                LOG.warn("PolyMail service did not stop cleanly: {}", e.getMessage());
            }
            polyMail = null;
        }
        io.shutdownNow();
    }

    /** Starts the PolyMail node from settings; safe to call when disabled. */
    public void startPolyMail() {
        stopPolyMail();
        try {
            var service = DesktopPolyMailService.fromSettings(context.paths(), settings);
            if (service.isEmpty()) {
                Platform.runLater(() -> {
                    polyMailStatus.setText(settings.polyMailEnabled() ? "PolyMail: not configured" : "");
                });
                return;
            }
            polyMail = service.get();
            compose.polyMailService(polyMail);
            polyMail.node().addListener(new com.graalmail.p2p.PolyMailNode.NodeListener() {
                @Override
                public void onStatus(com.graalmail.p2p.transport.NetworkStatus status) {
                    Platform.runLater(() -> polyMailStatus.setText("PolyMail: " + status));
                }

                @Override
                public void onScan(com.graalmail.p2p.delivery.ScanReport report) {
                    Platform.runLater(() -> {
                        if (report.delivered() > 0) {
                            polyMailStatus.setText("PolyMail: " + report.delivered() + " new");
                            reloadCurrentView();
                            if (settings.notificationsEnabled()) {
                                notifier.notifyNewMail(report.delivered());
                            }
                        } else {
                            polyMailStatus.setText("PolyMail: up to date");
                        }
                    });
                }

                @Override
                public void onError(String context, Throwable err) {
                    Platform.runLater(() -> polyMailStatus.setText("PolyMail: "
                            + (err.getMessage() == null ? context : err.getMessage())));
                }
            });
            polyMailStatus.setText("PolyMail: starting…");
            polyMail.start();
        } catch (RuntimeException e) {
            LOG.warn("PolyMail could not be started: {}", e.getMessage());
            polyMailStatus.setText("PolyMail: unavailable");
        }
    }

    private void stopPolyMail() {
        if (polyMail != null) {
            try {
                polyMail.close();
            } catch (RuntimeException e) {
                LOG.debug("PolyMail stop: {}", e.getMessage());
            }
            polyMail = null;
            compose.polyMailService(null);
        }
    }

    /** One PolyMail scan pass for manual refresh / RPC reconnect. */
    public void scanPolyMail() {
        if (polyMail == null) {
            return;
        }
        polyMailStatus.setText("PolyMail: scanning…");
        polyMail.scan().whenComplete((report, err) -> {
            if (err != null) {
                LOG.warn("PolyMail scan failed: {}", err.getMessage());
            }
        });
    }

    private void syncNow() {
        status.setText("Synchronising…");
        scanPolyMail();
        Thread worker = new Thread(() -> {
            try {
                var reports = context.syncEngine().syncAll();
                int newMessages = reports.stream().mapToInt(SyncReport::newMessages).sum();
                var needsTwoFactor = reports.stream()
                        .filter(report -> report.errors().stream()
                                .anyMatch(error -> error.toLowerCase(java.util.Locale.ROOT)
                                        .contains("two-factor code")))
                        .map(report -> context.store().account(report.accountId()).orElse(null))
                        .filter(java.util.Objects::nonNull)
                        .toList();
                Platform.runLater(() -> {
                    refreshFolders();
                    int errors = reports.stream().mapToInt(report -> report.errors().size()).sum();
                    status.setText(errors == 0 ? "Synchronised " + reports.size() + " account(s)"
                            : "Sync finished with " + errors + " issue(s)");
                    reloadCurrentView();
                    if (settings.notificationsEnabled() && newMessages > 0) {
                        notifier.notifyNewMail(newMessages);
                    }
                    if (!needsTwoFactor.isEmpty()) {
                        promptTwoFactorReauth(needsTwoFactor.get(0));
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

    /** Asks for a fresh TOTP code and refreshes the stored Proton session. */
    private void promptTwoFactorReauth(Account account) {
        var dialog = new javafx.scene.control.TextInputDialog();
        dialog.setTitle("Two-factor code needed");
        dialog.setHeaderText("Enter the two-factor code for " + account.email());
        dialog.setContentText("Authenticator code:");
        boolean maximized = UiScale.isMaximized(owner);
        var code = dialog.showAndWait();
        UiScale.restoreMaximized(owner, maximized);
        if (code.isEmpty() || code.get().isBlank()) {
            return;
        }
        status.setText("Verifying two-factor code…");
        io.submit(() -> {
            try {
                var fresh = context.sessions().reauthenticateWithTwoFactor(account, code.get().trim());
                Account updated = Account.builder(account.id(), account.providerId())
                        .email(account.email())
                        .displayName(account.displayName())
                        .settings(fresh.settings())
                        .credentialRef(account.credentialRef())
                        .createdAt(account.createdAt())
                        .enabled(account.enabled())
                        .build();
                context.store().saveAccount(updated);
                Platform.runLater(() -> {
                    status.setText("Two-factor verified. Synchronising…");
                    syncNow();
                });
            } catch (com.graalmail.core.error.ProviderException e) {
                LOG.warn("Two-factor re-authentication failed: {}", e.getMessage());
                Platform.runLater(() -> status.setText(
                        e.code() == com.graalmail.core.error.ErrorCode.CONSENT_REQUIRED
                                ? "Two-factor code not accepted. Try again on the next sync."
                                : "Two-factor verification failed: " + e.userMessage()));
            } catch (RuntimeException e) {
                LOG.warn("Two-factor re-authentication failed: {}", e.getMessage());
                Platform.runLater(() -> status.setText(
                        "Two-factor verification failed. Try again on the next sync."));
            }
        });
    }

    private void installShortcuts() {
        messageList.setOnKeyPressed(event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.R) {
                replyToSelected();
                event.consume();
            }
        });
    }

    private boolean isInsideReader(Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == reader) return true;
        }
        return false;
    }

    private boolean isInsideMessageList(Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == messageList || current == messageTable || current == listPane) return true;
        }
        return false;
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
        boolean maximized = UiScale.isMaximized(owner);
        compose.newMessage(account, owner);
        // Modeless window: re-check on the next pulse, right after the open.
        javafx.application.Platform.runLater(() -> UiScale.restoreMaximized(owner, maximized));
    }

    private void replyToSelected() {
        Message message = messageList.getSelectionModel().getSelectedItem();
        Account account = message == null ? null : context.store().account(message.accountId()).orElse(null);
        if (account == null) {
            showInfo("Select a message first.");
            return;
        }
        boolean maximized = UiScale.isMaximized(owner);
        compose.reply(account, message, owner);
        javafx.application.Platform.runLater(() -> UiScale.restoreMaximized(owner, maximized));
    }

    private void forwardSelected() {
        Message message = messageList.getSelectionModel().getSelectedItem();
        Account account = message == null ? null : context.store().account(message.accountId()).orElse(null);
        if (account == null) {
            showInfo("Select a message first.");
            return;
        }
        boolean maximized = UiScale.isMaximized(owner);
        compose.forward(account, message, owner);
        javafx.application.Platform.runLater(() -> UiScale.restoreMaximized(owner, maximized));
    }

    private Account firstAccount() {
        List<Account> accounts = context.store().listAccounts();
        return accounts.isEmpty() ? null : accounts.get(0);
    }

    private void showSettings() {
        boolean maximized = UiScale.isMaximized(owner);
        new SettingsDialog(settings, owner, isDarkTheme()).show();
        UiScale.restoreMaximized(owner, maximized);
        messageList.setCellFactory(view -> new MessageListCell(settings.compactDensity(), UiScale.scale(owner)));
        listModePicker.setValue(settings.listMode());
        updateListDisplayMode();
        rebuildWorkspace();
        startPolyMail();
        if (mainScene != null) {
            if (isDarkTheme()) {
                if (!mainScene.getRoot().getStyleClass().contains("dark-theme"))
                    mainScene.getRoot().getStyleClass().add("dark-theme");
            } else mainScene.getRoot().getStyleClass().remove("dark-theme");
        }
        if (currentMessage != null) reader.getEngine().loadContent(render(currentMessage, remoteImagesAllowed));
    }

    private void showDiagnostics() {
        boolean maximized = UiScale.isMaximized(owner);
        new DiagnosticsDialog(context, isDarkTheme()).show(owner);
        // Modeless: restore on the next pulse if the window manager dropped
        // the maximized state while opening the dialog.
        javafx.application.Platform.runLater(() -> UiScale.restoreMaximized(owner, maximized));
    }

    private void showInfo(String message) {
        boolean maximized = UiScale.isMaximized(owner);
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("GraalMail");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
        UiScale.restoreMaximized(owner, maximized);
    }

    private void addAccount() {
        boolean maximized = UiScale.isMaximized(owner);
        Stage stage = new Stage();
        stage.setTitle("Add account");
        AccountSetupDialog dialog = new AccountSetupDialog(context, stage);
        dialog.showAndWait(owner);
        UiScale.restoreMaximized(owner, maximized);
        refreshFolders();
    }

    /** Revokes remote tokens best-effort before local deletion. Runs on a worker thread. */
    private void revokeRemotely(Account account) {
        try {
            var provider = context.sessions().provider(account);
            var setup = context.sessions().setup(account, provider);
            provider.revoke(setup);
        } catch (RuntimeException e) {
            LOG.warn("Remote revocation for {} failed: {}", account.id(), e.getMessage());
        }
    }

    private void removeAccount(Account account) {
        if (account == null) {
            return;
        }
        String name = account.displayName().isBlank() ? account.email() : account.displayName();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Remove account");
        confirm.setHeaderText("Remove " + name + "?");
        confirm.setContentText("The account, its cached messages and its stored credentials "
                + "are deleted from this machine. Mail on the server is not touched.");
        boolean maximized = UiScale.isMaximized(owner);
        var choice = confirm.showAndWait();
        UiScale.restoreMaximized(owner, maximized);
        if (choice.filter(button -> button == javafx.scene.control.ButtonType.OK)
                .isEmpty()) {
            return;
        }
        status.setText("Removing " + name + "…");
        io.submit(() -> {
            revokeRemotely(account);
            try {
                context.sessions().deleteCredential(account);
            } catch (RuntimeException e) {
                LOG.warn("The credential of {} could not be deleted: {}", account.id(), e.getMessage());
            }
            try {
                context.store().deleteAccount(account.id());
            } catch (RuntimeException e) {
                Platform.runLater(() -> status.setText(
                        "The account could not be removed: " + e.getMessage()));
                return;
            }
            Platform.runLater(() -> {
                if (account.id().equals(settings.lastAccountId())) {
                    settings.lastAccountId("");
                    settings.lastFolderId("");
                }
                status.setText("Removed " + name + ".");
                refreshFolders();
            });
        });
    }

    private void showAbout() {
        boolean maximized = UiScale.isMaximized(owner);
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("GraalMail");
        alert.setHeaderText("GraalMail desktop");
        alert.setContentText("A cross platform email client. Data directory: "
                + context.paths().baseDirectory());
        alert.initOwner(owner);
        alert.show();
        alert.setOnHidden(event -> UiScale.restoreMaximized(owner, maximized));
    }

    private void showShortcuts() {
        boolean maximized = UiScale.isMaximized(owner);
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Keyboard shortcuts");
        alert.setHeaderText("Work faster without the mouse");
        alert.setContentText("""
                C — New message          Ctrl+N — New message
                / or Ctrl+K — Search     J / K — Next / previous message
                Enter — Focus reader     E or A — Archive
                U — Mark read / unread   S — Star / unstar
                Del or # — Delete        R — Reply   F — Forward
                Ctrl+R — Reply           Ctrl+Shift+F — Forward
                Ctrl+Shift+R — Sync      [ — Toggle folder pane""");
        alert.initOwner(owner);
        alert.show();
        alert.setOnHidden(event -> UiScale.restoreMaximized(owner, maximized));
    }

}
