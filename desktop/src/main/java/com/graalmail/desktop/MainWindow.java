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
    private final Button readerDetailsToggle = new Button("Show details");
    private VBox readerHeader;
    private final javafx.scene.layout.FlowPane attachmentActions = new javafx.scene.layout.FlowPane(8, 8);
    private final Label status = new Label("Ready");
    private final Label viewTitle = new Label("Unified Inbox");
    private final Label messageCount = new Label("0 messages");
    private final ToggleButton unreadFilter = new ToggleButton("Unread");
    private final ToggleButton starredFilter = new ToggleButton("Starred");
    private final ToggleButton attachmentsFilter = new ToggleButton("Attachments");
    private final Button clearFiltersButton = new Button("Clear filters");
    private final Button quickFilterToggle = new Button("Quick Filter");
    private final Button multiSelectButton = new Button("Select multiple");
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
    private Scene mainScene;
    private boolean remoteImagesAllowed;
    private boolean synchronizingSelection;
    private boolean hasMoreMessages;
    private int pageOffset;
    private int readerGeneration;
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
        if (isDarkTheme()) root.getStyleClass().add("dark-theme");
        VBox top = new VBox(buildToolbar());
        root.setTop(top);
        root.setCenter(buildCenter());
        root.setBottom(buildStatusBar());

        folderTree.getSelectionModel().selectedItemProperty().addListener((observable, old, item) ->
                onFolderSelected(item));
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
            } else if (event.getCode() == KeyCode.DELETE) {
                deleteSelected();
                event.consume();
            } else if (!event.isControlDown() && event.getCode() == KeyCode.A) {
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
        Button composeButton = new Button("New message");
        composeButton.getStyleClass().add("primary-action");
        composeButton.setOnAction(event -> composeNewMessage());
        Button refresh = new Button("Sync");
        refresh.setOnAction(event -> syncNow());
        Button addAccount = new Button("Add account");
        addAccount.setOnAction(event -> addAccount());
        searchField.setPromptText("Search mail");
        searchField.setPrefWidth(360);
        searchField.setMinWidth(220);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.setAccessibleText("Search cached mail");
        searchScope.getItems().setAll("All local mail");
        searchScope.setValue("All local mail");
        searchScope.setAccessibleText("Search scope");
        searchScope.setPrefWidth(150);
        Button settingsButton = new Button("Settings");
        settingsButton.setOnAction(event -> showSettings());
        MenuButton moreButton = new MenuButton("More");
        MenuItem diagnosticsItem = new MenuItem("Diagnostics…");
        diagnosticsItem.setOnAction(event -> showDiagnostics());
        MenuItem aboutItem = new MenuItem("About GraalMail");
        aboutItem.setOnAction(event -> showAbout());
        moreButton.getItems().addAll(diagnosticsItem, aboutItem);
        MenuButton layoutButton = new MenuButton("Layout");
        layoutButton.setAccessibleText("Choose workspace layout and pane visibility");
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
        });
        CheckMenuItem showReader = new CheckMenuItem("Show reading pane");
        showReader.setSelected(settings.showReader());
        showReader.setOnAction(event -> {
            settings.showReader(showReader.isSelected());
            rebuildWorkspace();
        });
        layoutButton.getItems().addAll(showFolders, showReader);
        multiSelectButton.setAccessibleText("Toggle multiple message selection");
        multiSelectButton.setOnAction(event -> {
            boolean multiple = messageList.getSelectionModel().getSelectionMode() == SelectionMode.SINGLE;
            messageList.getSelectionModel().setSelectionMode(multiple ? SelectionMode.MULTIPLE : SelectionMode.SINGLE);
            messageTable.getSelectionModel().setSelectionMode(multiple ? SelectionMode.MULTIPLE : SelectionMode.SINGLE);
            multiSelectButton.setText(multiple ? "Done selecting" : "Select multiple");
            if (!multiple) {
                var selected = messageList.getSelectionModel().getSelectedItem();
                messageList.getSelectionModel().clearSelection();
                if (selected != null) messageList.getSelectionModel().select(selected);
            }
            updateSelectionControls();
        });
        ToolBar toolbar = new ToolBar(composeButton, addAccount, refresh, layoutButton,
                new javafx.scene.control.Separator(), searchField, searchScope,
                settingsButton, moreButton);
        toolbar.getStyleClass().add("main-toolbar");
        toolbar.setId("main-toolbar");
        return toolbar;
    }

    private BorderPane buildCenter() {
        folderTree.setShowRoot(true);
        folderTree.setCellFactory(view -> new FolderTreeCell());
        folderTree.setPrefWidth(245);
        folderTree.setMinWidth(175);

        messageList.setPrefWidth(450);
        messageList.setMinWidth(300);
        messageList.setMinHeight(160);
        messageList.setCellFactory(view -> new MessageListCell(settings.compactDensity()));
        messageList.setPlaceholder(new Label("No messages here yet"));
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
        clearFiltersButton.setVisible(false);
        clearFiltersButton.setManaged(false);
        HBox filters = new HBox(6, unreadFilter, starredFilter, attachmentsFilter, clearFiltersButton);
        filters.setVisible(false);
        filters.setManaged(false);
        quickFilterToggle.setOnAction(event -> {
            boolean show = !filters.isVisible();
            filters.setVisible(show);
            filters.setManaged(show);
            quickFilterToggle.setText(show ? "Hide filters" : "Quick Filter");
        });
        listModePicker.getItems().setAll("Cards", "Table");
        listModePicker.setValue(settings.listMode());
        listModePicker.setAccessibleText("Message list display mode");
        listModePicker.valueProperty().addListener((observable, old, value) -> {
            settings.listMode(value);
            updateListDisplayMode();
        });
        HBox titleRow = new HBox(8, viewTitle, messageCount, listModePicker, multiSelectButton);
        titleRow.getChildren().add(quickFilterToggle);
        titleRow.getStyleClass().add("list-title-row");
        Button markSelectedRead = new Button("Read / unread");
        markSelectedRead.setOnAction(event -> toggleReadSelected());
        Button starSelected = new Button("Star / unstar");
        starSelected.setOnAction(event -> toggleStarSelected());
        Button archiveSelected = new Button("Archive");
        archiveSelected.setOnAction(event -> archiveSelected());
        Button deleteSelected = new Button("Delete");
        deleteSelected.setOnAction(event -> deleteSelected());
        HBox selectionActions = new HBox(6, selectionCount, markSelectedRead,
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
        VBox listContent = new VBox(8, titleRow, selectionActions, filters, messageViews, loadMoreButton);
        listContent.getStyleClass().add("message-list-pane");
        listContent.setMinWidth(300);
        VBox.setVgrow(messageViews, Priority.ALWAYS);
        listContent.setPadding(new Insets(12));
        listPane = listContent;

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
        Button moreActions = new Button("More actions");
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
        readerActionsPane = new javafx.scene.layout.FlowPane(
                7, 7, readerReplyButton, readerForwardButton,
                readerArchiveButton, readerDeleteButton, moreActions);
        readerActionsPane.setPadding(new Insets(10));
        readerActionsPane.getStyleClass().add("reader-actions");
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
        readerHeader = buildReaderHeader();
        readerPane = new VBox(readerActionsPane, readerHeader, remoteImagesButton,
                readerState, reader);
        readerPane.getStyleClass().add("reader-pane");
        reader.setContextMenuEnabled(false);
        reader.setMinWidth(320);
        reader.setMinHeight(200);
        readerPane.setMinWidth(340);
        HBox.setHgrow(reader, Priority.ALWAYS);
        VBox.setVgrow(reader, Priority.ALWAYS);

        Label foldersTitle = new Label("Mailboxes");
        foldersTitle.getStyleClass().add("pane-heading");
        Button addAccountButton = new Button("Add");
        addAccountButton.setAccessibleText("Add mail account");
        addAccountButton.setTooltip(new javafx.scene.control.Tooltip("Add account"));
        addAccountButton.setOnAction(event -> addAccount());
        javafx.scene.layout.Region navigationSpacer = new javafx.scene.layout.Region();
        HBox navigationHeader = new HBox(8, foldersTitle, navigationSpacer, addAccountButton);
        navigationHeader.setPadding(new Insets(10, 10, 6, 12));
        HBox.setHgrow(navigationSpacer, Priority.ALWAYS);
        navigationPane = new VBox(navigationHeader, folderTree);
        navigationPane.setMinWidth(175);
        navigationPane.setPrefWidth(245);
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
        HBox title = new HBox(8, readerSubject, spacer, readerDate);
        title.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(readerSubject, Priority.ALWAYS);
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox identity = new HBox(8, readerSender, readerMessageIndicators);
        identity.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        VBox header = new VBox(6, title, identity, readerRecipientSummary,
                readerDetailsToggle, readerRecipientDetails, attachmentActions);
        header.setPadding(new Insets(10, 14, 10, 14));
        header.getStyleClass().add("reader-header");
        header.setVisible(false);
        header.setManaged(false);
        readerRecipientDetails.setVisible(false);
        readerRecipientDetails.setManaged(false);
        return header;
    }

    private void showReaderHeader(Message message) {
        readerSubject.setText(message.subject().isBlank() ? "(No subject)" : message.subject());
        readerSender.setText(message.from().name().isBlank()
                ? message.from().email() : message.from().name() + "  <" + message.from().email() + ">");
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
        messageTable.setPlaceholder(new Label("No messages here yet"));
        messageTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<Message, String> state = new TableColumn<>("Status");
        state.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().unread() ? "Unread" : ""));
        state.setPrefWidth(72);
        TableColumn<Message, String> star = new TableColumn<>("Star");
        star.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().flagged() ? "★" : ""));
        star.setPrefWidth(52);
        TableColumn<Message, String> sender = new TableColumn<>("Sender");
        sender.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().from().name().isBlank()
                ? cell.getValue().from().email() : cell.getValue().from().name()));
        sender.setPrefWidth(150);
        TableColumn<Message, String> subject = new TableColumn<>("Subject");
        subject.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().subject().isBlank()
                ? "(No subject)" : cell.getValue().subject()));
        subject.setPrefWidth(230);
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
        date.setPrefWidth(150);
        TableColumn<Message, String> attachment = new TableColumn<>("Attachment");
        attachment.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue().hasAttachments() ? "Yes" : ""));
        attachment.setPrefWidth(88);
        messageTable.getColumns().setAll(state, star, sender, subject, date, attachment);
        messageTable.setMinWidth(300);
        messageTable.setMinHeight(160);
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
    }

    private SplitPane split(Orientation orientation, List<Node> children) {
        SplitPane pane = new SplitPane();
        pane.setOrientation(orientation);
        pane.getItems().setAll(children);
        pane.setStyle("-fx-background-color: transparent;");
        if (children.size() > 1) {
            String mode = settings.layoutMode();
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
            String mode = settings.layoutMode();
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
        String selected = settings.theme();
        if ("Dark".equals(selected)) return true;
        if ("Light".equals(selected)) return false;
        String gtk = System.getenv("GTK_THEME");
        String appearance = System.getProperty("apple.awt.application.appearance", "");
        String override = System.getProperty("graalmail.theme", "");
        if (!override.isBlank()) return "dark".equalsIgnoreCase(override);
        if (gtk != null && gtk.toLowerCase(java.util.Locale.ROOT).contains("dark")) return true;
        if (appearance.toLowerCase(java.util.Locale.ROOT).contains("dark")) return true;
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            try {
                return java.util.prefs.Preferences.userRoot()
                        .node("Software/Microsoft/Windows/CurrentVersion/Themes/Personalize")
                        .getInt("AppsUseLightTheme", 1) == 0;
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        return false;
    }

    private HBox buildStatusBar() {
        Label connection = new Label("Local cache");
        connection.getStyleClass().add("connection-indicator");
        HBox box = new HBox(10, connection, status);
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
        int generation = ++readerGeneration;
        currentMessage = message;
        updateReaderControls(message);
        attachmentActions.getChildren().clear();
        if (message == null) {
            readerState.setText(" ");
            readerHeader.setVisible(false);
            readerHeader.setManaged(false);
            remoteImagesAllowed = false;
            remoteImagesButton.setVisible(false);
            remoteImagesButton.setManaged(false);
            reader.getEngine().loadContent("<html><body><p>Select a message to read it.</p></body></html>");
            return;
        }
        remoteImagesAllowed = false;
        showReaderHeader(message);
        readerState.setText("Loading cached message…");
        remoteImagesButton.setVisible(false);
        remoteImagesButton.setManaged(false);
        reader.getEngine().loadContent("<html><body><p>Loading message from local cache…</p></body></html>");
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
                readerState.setText(pendingState);
                boolean remoteImages = containsRemoteImages(displayMessage.bodyHtml());
                remoteImagesButton.setText("Show remote images for this message");
                remoteImagesButton.setVisible(remoteImages);
                remoteImagesButton.setManaged(remoteImages);
                reader.getEngine().loadContent(render(displayMessage, false));
                showAttachments(displayMessage);
            });
        });
    }

    private String render(Message message) {
        return MessageBodyRenderer.render(message, false, isDarkTheme());
    }

    private String render(Message message, boolean allowRemoteImages) {
        return MessageBodyRenderer.render(message, allowRemoteImages, isDarkTheme());
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

    private void setActive(ToggleButton button, boolean active) {
        if (active) button.getStyleClass().add("active");
        else button.getStyleClass().remove("active");
    }

    private void refreshFilterControls() {
        boolean active = unreadOnly || flaggedOnly || attachmentsOnly;
        clearFiltersButton.setVisible(active);
        clearFiltersButton.setManaged(active);
    }

    private void updateSelectionControls() {
        int count = messageList.getSelectionModel().getSelectedItems().size();
        selectionCount.setText(count + " selected");
        boolean visible = count > 1
                || messageList.getSelectionModel().getSelectionMode() == SelectionMode.MULTIPLE;
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

    private boolean isInsideReader(Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == reader) return true;
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
        new SettingsDialog(settings, owner, isDarkTheme()).show();
        messageList.setCellFactory(view -> new MessageListCell(settings.compactDensity()));
        listModePicker.setValue(settings.listMode());
        updateListDisplayMode();
        rebuildWorkspace();
        if (mainScene != null) {
            if (isDarkTheme()) {
                if (!mainScene.getRoot().getStyleClass().contains("dark-theme"))
                    mainScene.getRoot().getStyleClass().add("dark-theme");
            } else mainScene.getRoot().getStyleClass().remove("dark-theme");
        }
        if (currentMessage != null) reader.getEngine().loadContent(render(currentMessage, remoteImagesAllowed));
    }

    private void showDiagnostics() {
        new DiagnosticsDialog(context, isDarkTheme()).show();
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
