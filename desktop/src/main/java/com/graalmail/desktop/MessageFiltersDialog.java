/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import com.graalmail.core.filter.FilterAction;
import com.graalmail.core.filter.FilterCondition;
import com.graalmail.core.filter.MessageFilter;
import com.graalmail.storage.MailStore;
import com.graalmail.core.util.Ids;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

/** Manages message filters (rules that file incoming mail automatically). */
public final class MessageFiltersDialog {

    private final MailStore store;
    private final Stage owner;
    private final boolean dark;

    public MessageFiltersDialog(MailStore store, Stage owner, boolean dark) {
        this.store = store;
        this.owner = owner;
        this.dark = dark;
    }

    public void show() {
        Stage stage = new Stage();
        stage.setTitle("Message Filters");
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);

        TableView<Row> table = new TableView<>();
        TableColumn<Row, String> name = new TableColumn<>("Name");
        name.setCellValueFactory(new PropertyValueFactory<>("name"));
        name.setPrefWidth(220);
        TableColumn<Row, String> state = new TableColumn<>("State");
        state.setCellValueFactory(new PropertyValueFactory<>("state"));
        state.setPrefWidth(90);
        TableColumn<Row, String> summary = new TableColumn<>("What it does");
        summary.setCellValueFactory(new PropertyValueFactory<>("summary"));
        summary.setPrefWidth(340);
        table.getColumns().addAll(name, state, summary);
        refresh(table);

        Button add = new Button("Add…");
        add.setOnAction(e -> edit(null, stage, table));
        Button edit = new Button("Edit…");
        edit.setOnAction(e -> {
            Row selected = table.getSelectionModel().getSelectedItem();
            if (selected != null) {
                edit(selected.filter(), stage, table);
            }
        });
        Button toggle = new Button("Enable/Disable");
        toggle.setOnAction(e -> {
            Row selected = table.getSelectionModel().getSelectedItem();
            if (selected != null) {
                MessageFilter filter = selected.filter();
                store.saveFilter(new MessageFilter(filter.id(), filter.accountId(), filter.name(),
                        !filter.enabled(), filter.matchAll(), filter.priority(),
                        filter.conditions(), filter.actions(), Instant.now()));
                refresh(table);
            }
        });
        Button delete = new Button("Delete");
        delete.setOnAction(e -> {
            Row selected = table.getSelectionModel().getSelectedItem();
            if (selected != null) {
                store.deleteFilter(selected.filter().id());
                refresh(table);
            }
        });
        Button close = new Button("Close");
        close.setOnAction(e -> stage.close());

        HBox buttons = new HBox(8, add, edit, toggle, delete, close);
        buttons.setPadding(new Insets(8));
        BorderPane root = new BorderPane(table, null, null, buttons, null);
        if (dark) {
            root.getStyleClass().add("dark-theme");
        }
        Scene scene = new Scene(root, 680, 420);
        stage.setScene(scene);
        stage.showAndWait();
    }

    private void refresh(TableView<Row> table) {
        List<Row> rows = new ArrayList<>();
        try {
            for (MessageFilter filter : store.listFilters()) {
                rows.add(new Row(filter));
            }
        } catch (RuntimeException ignored) {
        }
        table.setItems(FXCollections.observableArrayList(rows));
    }

    private void edit(MessageFilter existing, Stage parent, TableView<Row> table) {
        Stage stage = new Stage();
        stage.setTitle(existing == null ? "Add Filter" : "Edit Filter");
        stage.initOwner(parent);
        stage.initModality(Modality.WINDOW_MODAL);

        TextField nameField = new TextField(existing == null ? "" : existing.name());
        nameField.setPromptText("e.g. Invoices to Archive");
        TextField accountField = new TextField(existing == null ? "" : existing.accountId());
        accountField.setPromptText("Account id, blank = all accounts");
        ComboBox<String> matchBox = new ComboBox<>();
        matchBox.getItems().addAll("All conditions", "Any condition");
        matchBox.setValue(existing != null && !existing.matchAll() ? "Any condition" : "All conditions");
        Spinner<Integer> priority = new Spinner<>(0, 100, existing == null ? 10 : existing.priority());
        priority.setEditable(true);
        CheckBox enabled = new CheckBox("Enabled");
        enabled.setSelected(existing == null || existing.enabled());

        ListView<String> conditionList = new ListView<>();
        List<FilterCondition> conditions = new ArrayList<>(existing == null ? List.of() : existing.conditions());
        Runnable refreshConditions = () -> {
            List<String> labels = new ArrayList<>();
            for (FilterCondition condition : conditions) {
                labels.add(condition.field() + " " + condition.operator() + " “" + condition.value() + "”");
            }
            conditionList.setItems(FXCollections.observableArrayList(labels));
        };
        refreshConditions.run();

        ComboBox<FilterCondition.Field> fieldBox = new ComboBox<>();
        fieldBox.getItems().addAll(FilterCondition.Field.values());
        fieldBox.setValue(FilterCondition.Field.FROM);
        ComboBox<FilterCondition.Operator> opBox = new ComboBox<>();
        opBox.getItems().addAll(FilterCondition.Operator.values());
        opBox.setValue(FilterCondition.Operator.CONTAINS);
        TextField valueField = new TextField();
        valueField.setPromptText("value");
        Button addCondition = new Button("Add condition");
        addCondition.setOnAction(e -> {
            if (valueField.getText().isBlank() && fieldBox.getValue() != FilterCondition.Field.HAS_ATTACHMENT) {
                return;
            }
            conditions.add(new FilterCondition(fieldBox.getValue(), opBox.getValue(), valueField.getText()));
            valueField.clear();
            refreshConditions.run();
        });
        Button removeCondition = new Button("Remove");
        removeCondition.setOnAction(e -> {
            int index = conditionList.getSelectionModel().getSelectedIndex();
            if (index >= 0) {
                conditions.remove(index);
                refreshConditions.run();
            }
        });

        ListView<String> actionList = new ListView<>();
        List<FilterAction> actions = new ArrayList<>(existing == null ? List.of() : existing.actions());
        Runnable refreshActions = () -> {
            List<String> labels = new ArrayList<>();
            for (FilterAction action : actions) {
                labels.add(action.type() + (action.targetFolderId().isBlank() ? "" : " → " + action.targetFolderId()));
            }
            actionList.setItems(FXCollections.observableArrayList(labels));
        };
        refreshActions.run();
        ComboBox<FilterAction.Type> actionBox = new ComboBox<>();
        actionBox.getItems().addAll(FilterAction.Type.values());
        actionBox.setValue(FilterAction.Type.MARK_READ);
        TextField targetField = new TextField();
        targetField.setPromptText("folder id (for Move)");
        Button addAction = new Button("Add action");
        addAction.setOnAction(e -> {
            FilterAction.Type type = actionBox.getValue();
            String target = type == FilterAction.Type.MOVE_TO_FOLDER ? targetField.getText().trim() : "";
            if (type == FilterAction.Type.MOVE_TO_FOLDER && target.isBlank()) {
                return;
            }
            actions.add(new FilterAction(type, target));
            targetField.clear();
            refreshActions.run();
        });
        Button removeAction = new Button("Remove");
        removeAction.setOnAction(e -> {
            int index = actionList.getSelectionModel().getSelectedIndex();
            if (index >= 0) {
                actions.remove(index);
                refreshActions.run();
            }
        });

        Button save = new Button("Save");
        save.setOnAction(e -> {
            if (nameField.getText().isBlank() || conditions.isEmpty() || actions.isEmpty()) {
                return;
            }
            MessageFilter filter = new MessageFilter(
                    existing == null ? Ids.uuid() : existing.id(),
                    accountField.getText().trim(), nameField.getText().trim(), enabled.isSelected(),
                    "All conditions".equals(matchBox.getValue()), priority.getValue(),
                    conditions, actions, Instant.now());
            store.saveFilter(filter);
            refresh(table);
            stage.close();
        });
        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> stage.close());

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        form.setPadding(new Insets(12));
        form.add(new Label("Name:"), 0, 0);
        form.add(nameField, 1, 0);
        form.add(new Label("Account:"), 0, 1);
        form.add(accountField, 1, 1);
        form.add(new Label("Match:"), 0, 2);
        form.add(matchBox, 1, 2);
        form.add(new Label("Priority:"), 0, 3);
        form.add(priority, 1, 3);
        form.add(enabled, 1, 4);

        VBox conditionsBox = new VBox(6, new Label("Conditions:"), conditionList,
                new HBox(6, fieldBox, opBox, valueField, addCondition, removeCondition));
        conditionsBox.setPadding(new Insets(0, 12, 0, 12));
        VBox actionsBox = new VBox(6, new Label("Actions:"), actionList,
                new HBox(6, actionBox, targetField, addAction, removeAction));
        actionsBox.setPadding(new Insets(0, 12, 0, 12));
        HBox saveRow = new HBox(8, save, cancel);
        saveRow.setPadding(new Insets(12));
        VBox root = new VBox(form, conditionsBox, actionsBox, saveRow);
        Scene scene = new Scene(root, 640, 560);
        stage.setScene(scene);
        stage.showAndWait();
    }

    public static final class Row {
        private final MessageFilter filter;

        Row(MessageFilter filter) {
            this.filter = filter;
        }

        public MessageFilter filter() {
            return filter;
        }

        public String getName() {
            return filter.name();
        }

        public String getState() {
            return filter.enabled() ? "Enabled" : "Disabled";
        }

        public String getSummary() {
            return filter.conditions().size() + " condition(s), "
                    + filter.actions().size() + " action(s)"
                    + (filter.matchAll() ? " (all)" : " (any)");
        }
    }
}
