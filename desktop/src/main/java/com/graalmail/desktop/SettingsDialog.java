/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import com.graalmail.core.util.Log;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.Modality;
import org.slf4j.Logger;

/**
 * The settings window of the desktop application.
 *
 * <p>The values are written to the local database when the window is
 * confirmed.</p>
 */
public final class SettingsDialog {

    private static final Logger LOG = Log.of(SettingsDialog.class);

    private final DesktopSettings settings;
    private final Stage stage = new Stage();
    private final boolean darkTheme;

    private final Spinner<Integer> syncInterval;
    private final Spinner<Integer> pageSize;
    private final CheckBox notifications = new CheckBox("Show a desktop notification for new mail");
    private final ComboBox<String> density = new ComboBox<>();
    private final ComboBox<String> theme = new ComboBox<>();
    private final ComboBox<String> layout = new ComboBox<>();
    private final ComboBox<String> listMode = new ComboBox<>();

    /**
     * Creates the settings window.
     *
     * @param settings the settings facade
     */
    public SettingsDialog(DesktopSettings settings, Window owner, boolean darkTheme) {
        this.settings = settings;
        this.darkTheme = darkTheme;
        this.syncInterval = new Spinner<>(1, 240, settings.syncIntervalMinutes(), 1);
        this.pageSize = new Spinner<>(20, 1000, settings.pageSize(), 20);
        stage.setTitle("Settings");
        if (owner != null) {
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
        }
        stage.setResizable(true);
        stage.setMinWidth(680);
        stage.setMinHeight(480);
    }

    /**
     * Builds the scene of the window.
     *
     * @return the scene
     */
    public Scene build() {
        syncInterval.setEditable(true);
        pageSize.setEditable(true);
        notifications.setSelected(settings.notificationsEnabled());
        density.getItems().setAll("Comfortable", "Compact");
        density.setValue(settings.compactDensity() ? "Compact" : "Comfortable");
        theme.getItems().setAll("System", "Light", "Dark");
        theme.setValue(settings.theme());
        layout.getItems().setAll("Classic", "Wide", "Vertical");
        layout.setValue(settings.layoutMode());
        listMode.getItems().setAll("Cards", "Table");
        listMode.setValue(settings.listMode());

        Label intervalLabel = new Label("Synchronise every (minutes)");
        Label pageLabel = new Label("Messages per page");

        Button save = new Button("Save");
        save.setDefaultButton(true);
        save.setOnAction(event -> {
            settings.syncIntervalMinutes(syncInterval.getValue());
            settings.pageSize(pageSize.getValue());
            settings.notificationsEnabled(notifications.isSelected());
            settings.compactDensity("Compact".equals(density.getValue()));
            settings.theme(theme.getValue());
            settings.layoutMode(layout.getValue());
            settings.listMode(listMode.getValue());
            LOG.info("Settings saved: sync every {} minutes, {} messages per page",
                    syncInterval.getValue(), pageSize.getValue());
            stage.close();
        });
        Button cancel = new Button("Cancel");
        cancel.setOnAction(event -> stage.close());

        javafx.scene.layout.HBox buttons = new javafx.scene.layout.HBox(8, cancel, save);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        javafx.scene.layout.VBox syncSettings = new javafx.scene.layout.VBox(10,
                intervalLabel, syncInterval, pageLabel, pageSize, notifications);
        javafx.scene.layout.VBox appearanceSettings = new javafx.scene.layout.VBox(10,
                new Label("Message list display"), listMode,
                new Label("Message list density"), density, new Label("Theme"), theme);
        Label layoutHelp = new Label("Classic places the reader below the message list. Wide places it below the folder and list panes. Vertical shows all three panes side by side.");
        layoutHelp.setWrapText(true);
        javafx.scene.layout.VBox layoutSettings = new javafx.scene.layout.VBox(10,
                new Label("Workspace layout"), layout, layoutHelp,
                new Label("Pane visibility can also be changed from the Layout menu in the main toolbar."));
        javafx.scene.control.Tab syncTab = new javafx.scene.control.Tab("Sync", syncSettings);
        javafx.scene.control.Tab appearanceTab = new javafx.scene.control.Tab("Appearance", appearanceSettings);
        javafx.scene.control.Tab layoutTab = new javafx.scene.control.Tab("Layout", layoutSettings);
        syncTab.setClosable(false);
        appearanceTab.setClosable(false);
        layoutTab.setClosable(false);
        javafx.scene.control.TabPane tabs = new javafx.scene.control.TabPane(syncTab, appearanceTab, layoutTab);
        javafx.scene.layout.VBox root = new javafx.scene.layout.VBox(10, tabs, buttons);
        root.getStyleClass().add("account-setup");
        if (darkTheme) root.getStyleClass().add("dark-theme");
        root.setPadding(new Insets(14));

        Scene scene = new Scene(root, 820, 620);
        var stylesheet = getClass().getResource("/styles/mail.css");
        if (stylesheet != null) scene.getStylesheets().add(stylesheet.toExternalForm());
        stage.setScene(scene);
        return stage.getScene();
    }

    /**
     * Shows the window.
     */
    public void show() {
        if (stage.getScene() == null) {
            build();
        }
        stage.showAndWait();
    }
}
