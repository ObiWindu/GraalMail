/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import com.graalmail.core.domain.Account;
import java.util.Optional;
import com.graalmail.core.domain.LocalDraft;
import com.graalmail.core.domain.MessageDraft;
import java.util.Timer;
import java.util.TimerTask;
import com.graalmail.core.domain.Account;
import com.graalmail.core.domain.Address;
import com.graalmail.core.domain.Attachment;
import com.graalmail.core.domain.LocalDraft;
import com.graalmail.core.domain.Message;
import com.graalmail.core.domain.MessageDraft;
import com.graalmail.core.error.ProviderException;
import com.graalmail.core.recipient.RecipientSuggestion;
import com.graalmail.core.text.AddressParser;
import com.graalmail.core.text.TextTools;
import com.graalmail.core.util.Log;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graalmail.provider.api.ProviderSession;
import com.graalmail.provider.api.SendResult;
import com.graalmail.storage.RecipientSuggestionProvider;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import com.graalmail.storage.PendingAction;

/**
 * The compose window of the desktop application.
 *
 * <p>The window composes plain text messages, completes the recipient fields
 * from the local address book and sends or stores the message through the
 * provider session of the account.</p>
 */
public final class ComposeWindow {

    private static final Logger LOG = Log.of(ComposeWindow.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int SUGGESTION_LIMIT = 8;

    private final DesktopContext context;
    private final RecipientSuggestionProvider suggestions;

    private final Stage stage = new Stage();
    private final TextField toField = new TextField();
    private final TextField ccField = new TextField();
    private final TextField bccField = new TextField();
    private final TextField subjectField = new TextField();
    private final TextArea bodyArea = new TextArea();
    private final ListView<RecipientSuggestion> suggestionList = new ListView<>();
    private final ListView<Attachment> attachmentList = new ListView<>();
    private final VBox ccRow = labelled("Cc", ccField);
    private final VBox bccRow = labelled("Bcc", bccField);
    private final Label status = new Label(" ");
    private final Button sendButton = new Button("Send");
    private final Button draftButton = new Button("Save draft");
    private final Button attachButton = new Button("Attach files…");
    private final Button accountButton = new Button();

    private Account account;
    private Message replyTo;
    private boolean sending;
    private boolean allowClose;
    private Timer autosaveTimer;
    private String lastAutosavedDraftJson;
    private final List<Attachment> attachments = new ArrayList<>();
    private Path tempAttachmentsDir;

    /**
     * Creates the compose window.
     *
     * @param context the application context
     */
    public ComposeWindow(DesktopContext context) {
        this.context = context;
        this.suggestions = new RecipientSuggestionProvider(context.store());
        try {
            this.tempAttachmentsDir = Files.createTempDirectory("graalmail-attachments-");
        } catch (IOException e) {
            throw new RuntimeException("Failed to create temporary directory for attachments", e);
        }
        stage.setScene(build());
        
        // Clean up temp directory when window is closed
        stage.setOnHidden(event -> cleanupTempAttachments());
        stage.setOnCloseRequest(event -> {
            if (sending || allowClose || !hasComposeContent()) return;
            javafx.scene.control.ButtonType saveLocal = new javafx.scene.control.ButtonType(
                    "Save locally", javafx.scene.control.ButtonBar.ButtonData.YES);
            javafx.scene.control.ButtonType discard = new javafx.scene.control.ButtonType(
                    "Discard", javafx.scene.control.ButtonBar.ButtonData.NO);
            javafx.scene.control.ButtonType cancel = new javafx.scene.control.ButtonType(
                    "Keep editing", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                    "Save this message as a local draft before closing?",
                    saveLocal, discard, cancel);
            alert.setTitle("Unsaved message");
            alert.setHeaderText(null);
            javafx.scene.control.ButtonType choice = alert.showAndWait().orElse(cancel);
            if (choice == saveLocal) {
                saveLocalDraftOnClose();
            } else if (choice == discard) {
                if (account != null) context.store().getLocalDraft(account.id())
                        .ifPresent(draft -> context.store().deleteLocalDraft(draft.id()));
            } else {
                event.consume();
            }
        });
    }

    /**
     * Opens the window for a new message.
     *
     * @param account the account the message is sent from
     * @param owner the owner window, may be {@code null}
     */
    public void newMessage(Account account, Stage owner) {
        prepare(owner);
        this.account = account;
        this.allowClose = false;
        this.replyTo = null;
        checkAndRecoverDraft(account);
        toField.clear();
        ccField.clear();
        bccField.clear();
        subjectField.clear();
        bodyArea.clear();
        attachments.clear();
        attachmentList.getItems().clear();
        status.setText(" ");
        stage.setTitle("New message");
        stage.show();
        toField.requestFocus();
        if (accountButton.isVisible()) {
            accountButton.setText("From: " + account.email());
        }
    }

    /**
     * Opens the window with the reply of a message.
     *
     * @param account the account the reply is sent from
     * @param message the message that is answered
     * @param owner the owner window, may be {@code null}
     */
    public void reply(Account account, Message message, Stage owner) {
        prepare(owner);
        this.account = account;
        this.allowClose = false;
        this.replyTo = message;
        toField.clear();
        ccField.clear();
        bccField.clear();
        attachments.clear();
        attachmentList.getItems().clear();
        if (message != null) {
            List<Address> targets = new java.util.ArrayList<>();
            if (message.from().hasEmail()) {
                targets.add(message.from());
            }
            targets.addAll(message.to());
            targets.addAll(message.cc());
            toField.setText(AddressParser.formatList(unique(targets)));
            String subject = message.subject();
            subjectField.setText(subject.toLowerCase(java.util.Locale.ROOT).startsWith("re:")
                    ? subject : "Re: " + subject);
            bodyArea.setText(quote(message));
        }
        status.setText(" ");
        stage.setTitle("Reply");
        stage.show();
        bodyArea.requestFocus();
        if (accountButton.isVisible()) {
            accountButton.setText("From: " + account.email());
        }
    }

    /**
     * Opens the window with a forwarded message.
     *
     * @param account the account the message is forwarded from
     * @param message the message to forward
     * @param owner the owner window, may be {@code null}
     */
    public void forward(Account account, Message message, Stage owner) {
        prepare(owner);
        this.account = account;
        this.allowClose = false;
        this.replyTo = message;
        toField.clear();
        ccField.clear();
        bccField.clear();
        attachments.clear();
        attachmentList.getItems().clear();
        if (message != null) {
            String subject = message.subject();
            subjectField.setText(subject.toLowerCase(java.util.Locale.ROOT).startsWith("fwd:")
                    ? subject : "Fwd: " + subject);
            bodyArea.setText(quote(message));
        }
        status.setText(" ");
        stage.setTitle("Forward");
        stage.show();
        bodyArea.requestFocus();
        if (accountButton.isVisible()) {
            accountButton.setText("From: " + account.email());
        }
    }

    /**
     * Shows the window modally.
     *
     * @param owner the owner window
     */
    public void showAndWait(Stage owner) {
        prepare(owner);
        stage.showAndWait();
    }

    private void initAutosave() {
        autosaveTimer = new Timer(true);
        autosaveTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                javafx.application.Platform.runLater(() -> autosave());
            }
        }, 30_000, 30_000); // Autosave every 30 seconds
    }

    private void autosave() {
        if (account == null || sending) {
            return;
        }
        MessageDraft draft = buildDraft();
        if (draft == null) {
            return;
        }
        String draftJson;
        try {
            draftJson = MAPPER.writeValueAsString(draft);
        } catch (Exception e) {
            return;
        }
        if (draftJson.equals(lastAutosavedDraftJson)) {
            return; // No changes
        }
        lastAutosavedDraftJson = draftJson;
        LocalDraft localDraft = LocalDraft.of(account.id(), "", draftJson);
        context.store().saveLocalDraft(localDraft);
        // Update status silently
        String currentStatus = status.getText();
        if (!currentStatus.startsWith("Autosaved")) {
            status.setText("Autosaved at " + java.time.LocalTime.now().toString().substring(0, 5));
        }
    }

    private void checkAndRecoverDraft(Account account) {
        Optional<LocalDraft> draftOpt = context.store().getLocalDraft(account.id());
        if (draftOpt.isPresent()) {
            LocalDraft localDraft = draftOpt.get();
            if (localDraft.recovered()) {
                return; // Already recovered
            }
            javafx.application.Platform.runLater(() -> {
                javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                        javafx.scene.control.Alert.AlertType.CONFIRMATION);
                alert.setTitle("Recover draft?");
                alert.setHeaderText("An unsaved draft was found for " + account.email());
                alert.setContentText("Do you want to restore the draft?");
                alert.getButtonTypes().setAll(javafx.scene.control.ButtonType.YES, javafx.scene.control.ButtonType.NO);
                alert.showAndWait().ifPresent(response -> {
                    if (response == javafx.scene.control.ButtonType.YES) {
                        restoreDraft(localDraft);
                    } else {
                        context.store().deleteLocalDraft(localDraft.id());
                    }
                });
            });
        }
    }

    private void restoreDraft(LocalDraft localDraft) {
        try {
            MessageDraft draft = MAPPER.readValue(localDraft.draftJson(), MessageDraft.class);
            toField.setText(AddressParser.formatList(draft.to()));
            ccField.setText(AddressParser.formatList(draft.cc()));
            bccField.setText(AddressParser.formatList(draft.bcc()));
            subjectField.setText(draft.subject());
            bodyArea.setText(draft.bodyText());
            
            // Restore attachments
            attachments.clear();
            attachmentList.getItems().clear();
            if (draft.attachments() != null) {
                for (Attachment attachment : draft.attachments()) {
                    attachments.add(attachment);
                    attachmentList.getItems().add(attachment);
                }
            }
            
            status.setText("Draft recovered");
            context.store().saveLocalDraft(localDraft.withRecovered(true));
        } catch (Exception e) {
            LOG.warn("Failed to recover draft: {}", e.getMessage());
        }
    }

    private void prepare(Stage owner) {
        var classes = stage.getScene().getRoot().getStyleClass();
        if (new DesktopSettings(context.store()).theme().equals("Dark")) {
            if (!classes.contains("dark-theme")) classes.add("dark-theme");
        } else classes.remove("dark-theme");
        if (owner != null && stage.getOwner() == null) {
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
        }
    }

    /**
     * Builds the scene of the window.
     *
     * @return the scene
     */
    public Scene build() {
        VBox root = new VBox(8);
        root.getStyleClass().add("compose-window");
        if (new DesktopSettings(context.store()).theme().equals("Dark")) root.getStyleClass().add("dark-theme");
        root.setPadding(new Insets(10));
        Label heading = new Label("Compose message");
        heading.getStyleClass().add("compose-heading");

        Button recipientOptions = new Button("Cc / Bcc");
        recipientOptions.setAccessibleText("Show or hide carbon copy and blind carbon copy fields");
        ccRow.setVisible(false);
        ccRow.setManaged(false);
        bccRow.setVisible(false);
        bccRow.setManaged(false);
        recipientOptions.setOnAction(event -> {
            boolean show = !ccRow.isVisible();
            ccRow.setVisible(show);
            ccRow.setManaged(show);
            bccRow.setVisible(show);
            bccRow.setManaged(show);
        });
        HBox toRow = new HBox(8, labelled("To", toField), recipientOptions);
        HBox.setHgrow(toRow.getChildren().getFirst(), Priority.ALWAYS);
        root.getChildren().addAll(toRow, ccRow, bccRow, labelled("Subject", subjectField));
        root.getChildren().add(0, heading);

        suggestionList.setPrefHeight(120);
        suggestionList.setVisible(false);
        suggestionList.setOnMouseClicked(event -> {
            RecipientSuggestion selected = suggestionList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                applySuggestion(selected);
            }
        });

        // Account selection
        List<Account> accounts = context.store().listAccounts();
        if (accounts.size() > 1) {
            accountButton.setText("From: " + (account != null ? account.email() : "Select account"));
            accountButton.setOnAction(e -> showAccountSelection());
        } else {
            accountButton.setVisible(false);
        }

        bodyArea.setPrefRowCount(18);
        VBox.setVgrow(bodyArea, Priority.ALWAYS);

        // Attachments
        attachmentList.setPrefHeight(80);
        attachmentList.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(Attachment item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    HBox box = new HBox(8);
                    Label name = new Label(item.fileName());
                    Label size = new Label(TextTools.formatBytes(item.size()));
                    size.setTextFill(javafx.scene.paint.Color.DIMGREY);
                    Button remove = new Button("Remove");
                    remove.setAccessibleText("Remove attachment " + item.fileName());
                    remove.setOnAction(e -> removeAttachment(item));
                    box.getChildren().addAll(name, size, remove);
                    HBox.setHgrow(name, Priority.ALWAYS);
                    setGraphic(box);
                }
            }
        });

        status.setTextFill(javafx.scene.paint.Color.DIMGREY);

        HBox buttons = new HBox(8, accountButton, draftButton, attachButton, sendButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        root.getChildren().addAll(suggestionList, bodyArea, attachmentList, status, buttons);

        sendButton.setDefaultButton(true);
        sendButton.getStyleClass().add("primary-action");
        sendButton.setOnAction(event -> send());
        draftButton.setOnAction(event -> saveDraft());
        attachButton.setOnAction(event -> pickAttachments());

        wireSuggestions();
        wireShortcuts();

        Scene scene = new Scene(root, 760, 680);
        var stylesheet = getClass().getResource("/styles/mail.css");
        if (stylesheet != null) scene.getStylesheets().add(stylesheet.toExternalForm());
        stage.setScene(scene);
        initAutosave();
        return stage.getScene();
    }

    private VBox labelled(String label, TextField field) {
        Label caption = new Label(label);
        caption.setMinWidth(60);
        HBox row = new HBox(8, caption, field);
        HBox.setHgrow(field, Priority.ALWAYS);
        VBox box = new VBox(row);
        return box;
    }

    private void wireSuggestions() {
        toField.focusedProperty().addListener((observable, old, focused) -> {
            if (!focused) {
                hideSuggestions();
            }
        });
        toField.textProperty().addListener((observable, old, value) -> updateSuggestions(value));
        ccField.textProperty().addListener((observable, old, value) -> updateSuggestions(value));
    }

    private void wireShortcuts() {
        toField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER && suggestionList.isVisible()) {
                RecipientSuggestion selected = suggestionList.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    applySuggestion(selected);
                    event.consume();
                }
            }
        });
    }

    private void updateSuggestions(String value) {
        if (value == null) {
            hideSuggestions();
            return;
        }
        String term = currentTerm(value);
        List<RecipientSuggestion> matches = suggestions.suggest(term, SUGGESTION_LIMIT);
        if (matches.isEmpty()) {
            hideSuggestions();
            return;
        }
        suggestionList.getItems().setAll(matches);
        suggestionList.getSelectionModel().selectFirst();
        suggestionList.setVisible(true);
        FadeTransition fade = new FadeTransition(Duration.millis(120), suggestionList);
        fade.setFromValue(0.4);
        fade.setToValue(1.0);
        fade.play();
    }

    private void hideSuggestions() {
        suggestionList.setVisible(false);
    }

    private String currentTerm(String value) {
        int comma = Math.max(value.lastIndexOf(','), value.lastIndexOf(';'));
        return comma < 0 ? value : value.substring(comma + 1).trim();
    }

    private void applySuggestion(RecipientSuggestion suggestion) {
        TextField field = toField.isFocused() ? toField : ccField;
        String value = field.getText();
        int comma = Math.max(value.lastIndexOf(','), value.lastIndexOf(';'));
        String prefix = comma < 0 ? "" : value.substring(0, comma + 1) + " ";
        field.setText(prefix + suggestion.format());
        field.positionCaret(field.getText().length());
        hideSuggestions();
    }

    private void send() {
        MessageDraft draft = buildDraft();
        if (draft == null) {
            return;
        }
        sending = true;
        sendButton.setDisable(true);
        draftButton.setDisable(true);
        attachButton.setDisable(true);
        status.setText("Sending…");
        
        String draftJson;
        try {
            draftJson = MAPPER.writeValueAsString(draft);
        } catch (Exception e) {
            showWarning("Failed to prepare message for sending.");
            sending = false;
            sendButton.setDisable(false);
            draftButton.setDisable(false);
            attachButton.setDisable(false);
            return;
        }
        
        runInBackground(() -> {
            try (ProviderSession session = context.sessions().open(account)) {
                SendResult result = session.submission().send(draft);
                if (!result.accepted() && result.ambiguous()) {
                    // Queue for retry with ambiguous outcome
                    String actionId = java.util.UUID.randomUUID().toString();
                    PendingAction action = PendingAction.of(actionId, account.id(), 
                            PendingAction.Type.SEND, "", draftJson);
                    action = action.withSendState(PendingAction.SendState.SUBMISSION_OUTCOME_UNKNOWN);
                    context.store().enqueue(action);
                    return "Message sent but delivery not confirmed. Check sent folder before resending.";
                }
                // Clear autosaved draft
                context.store().getLocalDraft(account.id()).ifPresent(d -> context.store().deleteLocalDraft(d.id()));
                lastAutosavedDraftJson = null;
                return "Message sent";
            }
        });
    }

    private void saveDraft() {
        MessageDraft draft = buildDraft();
        if (draft == null) {
            return;
        }
        sending = true;
        sendButton.setDisable(true);
        draftButton.setDisable(true);
        attachButton.setDisable(true);
        status.setText("Saving draft...");
        
        String draftJson;
        try {
            draftJson = MAPPER.writeValueAsString(draft);
        } catch (Exception e) {
            showWarning("Failed to prepare draft for saving.");
            sending = false;
            sendButton.setDisable(false);
            draftButton.setDisable(false);
            attachButton.setDisable(false);
            return;
        }
        
        runInBackground(() -> {
            try (ProviderSession session = context.sessions().open(account)) {
                session.submission().saveDraft(draft);
                // Clear autosaved draft
                context.store().getLocalDraft(account.id()).ifPresent(d -> context.store().deleteLocalDraft(d.id()));
                lastAutosavedDraftJson = null;
                return "Draft saved";
            }
        });
    }

    private MessageDraft buildDraft() {
        if (account == null) {
            showWarning("No account is selected.");
            return null;
        }
        List<Address> to = AddressParser.parseList(toField.getText());
        List<Address> cc = AddressParser.parseList(ccField.getText());
        List<Address> bcc = AddressParser.parseList(bccField.getText());
        if (to.isEmpty() && cc.isEmpty() && bcc.isEmpty()) {
            showWarning("At least one recipient is required.");
            return null;
        }
        if (AddressParser.hasInvalid(to) || AddressParser.hasInvalid(cc) || AddressParser.hasInvalid(bcc)) {
            showWarning("One of the recipient addresses is not valid.");
            return null;
        }
        MessageDraft.Builder builder = MessageDraft.builder()
                .accountId(account.id())
                .to(to)
                .cc(cc)
                .bcc(bcc)
                .subject(subjectField.getText())
                .bodyText(bodyArea.getText())
                .attachments(new ArrayList<>(attachments));
        if (replyTo != null) {
            builder.inReplyTo(replyTo.internetMessageId())
                    .references(replyTo.internetMessageId());
        }
        return builder.build();
    }

    private void runInBackground(java.util.function.Supplier<String> work) {
        Thread worker = new Thread(() -> {
            String result;
            try {
                result = work.get();
            } catch (ProviderException e) {
                result = e.getMessage();
            } catch (RuntimeException e) {
                LOG.warn("The message could not be processed: {}", e.getMessage());
                result = "Failed: " + e.getMessage();
            }
            String message = result;
            javafx.application.Platform.runLater(() -> {
                sending = false;
                sendButton.setDisable(false);
                draftButton.setDisable(false);
                attachButton.setDisable(false);
                status.setText(message);
                if (message.endsWith("sent") || message.endsWith("saved") || message.contains("not confirmed")) {
                    allowClose = true;
                    stage.close();
                }
            });
        }, "graalmail-compose");
        worker.setDaemon(true);
        worker.start();
    }

    private boolean hasComposeContent() {
        return !toField.getText().isBlank() || !ccField.getText().isBlank()
                || !bccField.getText().isBlank() || !subjectField.getText().isBlank()
                || !bodyArea.getText().isBlank() || !attachments.isEmpty();
    }

    private void saveLocalDraftOnClose() {
        if (account == null) return;
        MessageDraft draft = MessageDraft.builder().accountId(account.id())
                .to(AddressParser.parseList(toField.getText()))
                .cc(AddressParser.parseList(ccField.getText()))
                .bcc(AddressParser.parseList(bccField.getText()))
                .subject(subjectField.getText())
                .bodyText(bodyArea.getText())
                .attachments(new ArrayList<>(attachments)).build();
        try {
            String json = MAPPER.writeValueAsString(draft);
            context.store().saveLocalDraft(LocalDraft.of(account.id(), "", json));
        } catch (Exception e) {
            LOG.warn("The local draft could not be saved: {}", e.getMessage());
        }
    }

    private void showWarning(String message) {
        status.setText(message);
        javafx.scene.control.Alert alert =
                new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING);
        alert.setTitle("GraalMail");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private static List<Address> unique(List<Address> addresses) {
        List<Address> result = new java.util.ArrayList<>();
        for (Address address : addresses) {
            if (address == null || !address.hasEmail()) {
                continue;
            }
            boolean known = false;
            for (Address existing : result) {
                if (existing.email().equalsIgnoreCase(address.email())) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                result.add(address);
            }
        }
        return result;
    }

    private static String quote(Message message) {
        StringBuilder sb = new StringBuilder();
        String from = message.from().displayLabel();
        sb.append("On ").append(message.date() == null ? "" : message.date().toString())
                .append(", ").append(from).append(" wrote:\n");
        sb.append("> ").append(message.bodyText().replace("\n", "\n> ")).append("\n");
        return sb.toString();
    }

    /**
     * The tooltip that explains the reply behaviour of the window.
     *
     * @return the tooltip text
     */
    public String helpText() {
        return TextTools.snippet("Ctrl+Enter sends the message, Esc closes the window.", 120);
    }

    private void pickAttachments() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select attachments");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("All Files", "*.*"),
                new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"),
                new FileChooser.ExtensionFilter("Documents", "*.pdf", "*.doc", "*.docx", "*.txt", "*.rtf"),
                new FileChooser.ExtensionFilter("Archives", "*.zip", "*.tar", "*.gz", "*.rar")
        );
        List<File> files = fileChooser.showOpenMultipleDialog(stage);
        if (files != null) {
            for (File file : files) {
                addAttachment(file.toPath());
            }
        }
    }

    private void addAttachment(Path path) {
        try {
            long size = Files.size(path);
            if (size > 25 * 1024 * 1024) { // 25 MB limit
                showWarning("Attachment too large (max 25 MB): " + path.getFileName());
                return;
            }
            // Copy to temp directory for safe keeping
            Path tempFile = tempAttachmentsDir.resolve(UUID.randomUUID().toString() + "_" + path.getFileName());
            Files.copy(path, tempFile, StandardCopyOption.REPLACE_EXISTING);
            
            String mimeType = Files.probeContentType(path);
            if (mimeType == null) {
                mimeType = "application/octet-stream";
            }
            
            Attachment attachment = new Attachment(
                    tempFile.toString(),
                    path.getFileName().toString(),
                    mimeType,
                    size,
                    false,
                    ""
            );
            attachments.add(attachment);
            attachmentList.getItems().add(attachment);
        } catch (IOException e) {
            LOG.warn("Failed to add attachment: {}", e.getMessage());
            showWarning("Failed to add attachment: " + e.getMessage());
        }
    }

    private void removeAttachment(Attachment attachment) {
        attachments.remove(attachment);
        attachmentList.getItems().remove(attachment);
        try {
            Files.deleteIfExists(Path.of(attachment.contentId()));
        } catch (IOException e) {
            LOG.warn("Failed to delete temporary attachment file: {}", e.getMessage());
        }
    }

    private void showAccountSelection() {
        List<Account> accounts = context.store().listAccounts();
        javafx.scene.control.ChoiceDialog<Account> dialog = new javafx.scene.control.ChoiceDialog<>(
                account != null ? account : accounts.get(0), accounts);
        dialog.setTitle("Select Account");
        dialog.setHeaderText("Choose the account to send from");
        dialog.setContentText("Account:");
        dialog.showAndWait().ifPresent(selected -> {
            this.account = selected;
            accountButton.setText("From: " + selected.email());
            // Update draft if it exists
            if (account != null) {
                checkAndRecoverDraft(account);
            }
        });
    }

    /**
     * Cleans up the temporary attachments directory.
     */
    private void cleanupTempAttachments() {
        if (tempAttachmentsDir != null) {
            try {
                Files.walk(tempAttachmentsDir)
                        .sorted((a, b) -> b.compareTo(a))
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException e) {
                                LOG.warn("Failed to delete temporary file {}: {}", path, e.getMessage());
                            }
                        });
            } catch (IOException e) {
                LOG.warn("Failed to clean up temporary attachments directory: {}", e.getMessage());
            }
        }
    }

    /**
     * Whether the window is currently sending or saving a message.
     *
     * @return {@code true} while a background action runs
     */
    public boolean busy() {
        return sending;
    }
}
