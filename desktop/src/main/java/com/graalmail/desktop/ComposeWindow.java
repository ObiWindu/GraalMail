/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import com.graalmail.core.domain.Account;
import com.graalmail.core.domain.Address;
import com.graalmail.core.domain.Message;
import com.graalmail.core.domain.MessageDraft;
import com.graalmail.core.error.ProviderException;
import com.graalmail.core.recipient.RecipientSuggestion;
import com.graalmail.core.text.AddressParser;
import com.graalmail.core.text.TextTools;
import com.graalmail.core.util.Log;
import com.graalmail.provider.api.ProviderSession;
import com.graalmail.provider.api.SendResult;
import com.graalmail.storage.RecipientSuggestionProvider;
import java.util.List;
import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;

/**
 * The compose window of the desktop application.
 *
 * <p>The window composes plain text messages, completes the recipient fields
 * from the local address book and sends or stores the message through the
 * provider session of the account.</p>
 */
public final class ComposeWindow {

    private static final Logger LOG = Log.of(ComposeWindow.class);
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
    private final Label status = new Label(" ");
    private final Button sendButton = new Button("Send");
    private final Button draftButton = new Button("Save draft");

    private Account account;
    private Message replyTo;
    private boolean sending;

    /**
     * Creates the compose window.
     *
     * @param context the application context
     */
    public ComposeWindow(DesktopContext context) {
        this.context = context;
        this.suggestions = new RecipientSuggestionProvider(context.store());
        stage.setScene(build());
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
        this.replyTo = null;
        toField.clear();
        ccField.clear();
        bccField.clear();
        subjectField.clear();
        bodyArea.clear();
        status.setText(" ");
        stage.setTitle("New message");
        stage.show();
        toField.requestFocus();
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
        this.replyTo = message;
        toField.clear();
        ccField.clear();
        bccField.clear();
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

    private void prepare(Stage owner) {
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
        root.setPadding(new Insets(10));

        root.getChildren().addAll(
                labelled("To", toField),
                labelled("Cc", ccField),
                labelled("Bcc", bccField),
                labelled("Subject", subjectField));

        suggestionList.setPrefHeight(120);
        suggestionList.setVisible(false);
        suggestionList.setOnMouseClicked(event -> {
            RecipientSuggestion selected = suggestionList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                applySuggestion(selected);
            }
        });

        bodyArea.setPrefRowCount(18);
        VBox.setVgrow(bodyArea, Priority.ALWAYS);

        status.setTextFill(javafx.scene.paint.Color.DIMGREY);

        HBox buttons = new HBox(8, draftButton, sendButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        root.getChildren().addAll(suggestionList, bodyArea, status, buttons);

        sendButton.setDefaultButton(true);
        sendButton.setOnAction(event -> send());
        draftButton.setOnAction(event -> saveDraft());

        wireSuggestions();
        wireShortcuts();

        stage.setScene(new Scene(root, 720, 640));
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
        status.setText("Sending…");
        runInBackground(() -> {
            try (ProviderSession session = context.sessions().open(account)) {
                SendResult result = session.submission().send(draft);
                if (!result.accepted() && result.ambiguous()) {
                    showWarning("The message was sent but the server connection dropped before the "
                            + "result was confirmed. Check the sent folder before sending again.");
                }
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
        draftButton.setDisable(true);
        status.setText("Saving draft…");
        runInBackground(() -> {
            try (ProviderSession session = context.sessions().open(account)) {
                session.submission().saveDraft(draft);
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
                .bodyText(bodyArea.getText());
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
                status.setText(message);
                if (message.endsWith("sent") || message.endsWith("saved")) {
                    stage.close();
                }
            });
        }, "graalmail-compose");
        worker.setDaemon(true);
        worker.start();
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

    /**
     * Whether the window is currently sending or saving a message.
     *
     * @return {@code true} while a background action runs
     */
    public boolean busy() {
        return sending;
    }
}
