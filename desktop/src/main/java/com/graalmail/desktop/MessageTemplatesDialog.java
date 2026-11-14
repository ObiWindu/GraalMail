/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import com.graalmail.core.filter.MessageTemplate;
import com.graalmail.core.util.Ids;
import com.graalmail.storage.MailStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

/** Manages canned responses inserted from the compose window. */
public final class MessageTemplatesDialog {

    private final MailStore store;
    private final Stage owner;
    private final boolean dark;

    public MessageTemplatesDialog(MailStore store, Stage owner, boolean dark) {
        this.store = store;
        this.owner = owner;
        this.dark = dark;
    }

    public void show() {
        Stage stage = new Stage();
        stage.setTitle("Message Templates");
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);

        ListView<String> list = new ListView<>();
        refresh(list);

        TextField nameField = new TextField();
        nameField.setPromptText("Template name");
        TextField subjectField = new TextField();
        subjectField.setPromptText("Subject (optional)");
        TextArea bodyArea = new TextArea();
        bodyArea.setPromptText("Body text…");
        bodyArea.setPrefRowCount(6);

        Button save = new Button("Save Template");
        save.setOnAction(e -> {
            if (nameField.getText().isBlank() || bodyArea.getText().isBlank()) {
                return;
            }
            store.saveTemplate(new MessageTemplate(Ids.uuid(), "", nameField.getText().trim(),
                    subjectField.getText().trim(), bodyArea.getText(), Instant.now()));
            nameField.clear();
            subjectField.clear();
            bodyArea.clear();
            refresh(list);
        });
        Button delete = new Button("Delete Selected");
        delete.setOnAction(e -> {
            int index = list.getSelectionModel().getSelectedIndex();
            if (index < 0) {
                return;
            }
            try {
                List<MessageTemplate> templates = store.listTemplates();
                if (index < templates.size()) {
                    store.deleteTemplate(templates.get(index).id());
                    refresh(list);
                }
            } catch (RuntimeException ignored) {
            }
        });
        Button close = new Button("Close");
        close.setOnAction(e -> stage.close());

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        form.add(new Label("Name:"), 0, 0);
        form.add(nameField, 1, 0);
        form.add(new Label("Subject:"), 0, 1);
        form.add(subjectField, 1, 1);

        VBox center = new VBox(8, list, form, new Label("Body:"), bodyArea);
        center.setPadding(new Insets(12));
        HBox buttons = new HBox(8, save, delete, close);
        buttons.setPadding(new Insets(0, 12, 12, 12));
        BorderPane root = new BorderPane(center, null, null, buttons, null);
        if (dark) {
            root.getStyleClass().add("dark-theme");
        }
        stage.setScene(new Scene(root, 560, 520));
        stage.showAndWait();
    }

    private void refresh(ListView<String> list) {
        List<String> labels = new ArrayList<>();
        try {
            for (MessageTemplate template : store.listTemplates()) {
                labels.add(template.name() + (template.subject().isBlank() ? "" : " — " + template.subject()));
            }
        } catch (RuntimeException ignored) {
        }
        list.setItems(FXCollections.observableArrayList(labels));
    }
}
