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
    private final CheckBox showNavigation = new CheckBox("Show folders and accounts pane");
    private final CheckBox showReader = new CheckBox("Show message reader pane");
    private final CheckBox polyMailEnabled = new CheckBox("Enable PolyMail decentralized delivery");
    private final javafx.scene.control.TextField polyMailIdentity = new javafx.scene.control.TextField();
    private final javafx.scene.control.TextField polyMailRpcUrls = new javafx.scene.control.TextField();
    private final javafx.scene.control.TextField polyMailContract = new javafx.scene.control.TextField();
    private final javafx.scene.control.TextField polyMailChainId = new javafx.scene.control.TextField();
    private final ComboBox<String> polyMailNetwork = new ComboBox<>();
    private final Spinner<Integer> polyMailPort;
    private final javafx.scene.control.TextField polyMailMailboxPeers =
            new javafx.scene.control.TextField();

    /**
     * Creates the settings window.
     *
     * @param settings the settings facade
     */
    public SettingsDialog(DesktopSettings settings, Window owner, boolean darkTheme) {
        this.settings = settings;
        this.darkTheme = darkTheme;
        this.syncInterval = new Spinner<>(1, 240, clamp(settings.syncIntervalMinutes(), 1, 240), 1);
        this.pageSize = new Spinner<>(20, 1000, clamp(settings.pageSize(), 20, 1000), 20);
        this.polyMailPort = new Spinner<>(0, 65535, clamp(settings.polyMailListenPort(), 0, 65535), 1);
        stage.setTitle("Settings");
        if (owner != null) {
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
        }
        stage.setResizable(true);
    }

    /**
     * Builds the scene of the window.
     *
     * @return the scene
     */
    public Scene build() {
        syncInterval.setEditable(true);
        pageSize.setEditable(true);
        polyMailPort.setEditable(true);
        commitSpinnerOnFocusLoss(syncInterval, 1, 240);
        commitSpinnerOnFocusLoss(pageSize, 20, 1000);
        commitSpinnerOnFocusLoss(polyMailPort, 0, 65535);
        notifications.setSelected(settings.notificationsEnabled());
        density.getItems().setAll("Comfortable", "Compact");
        density.setValue(settings.compactDensity() ? "Compact" : "Comfortable");
        theme.getItems().setAll("System", "Light", "Dark");
        theme.setValue(settings.theme());
        layout.getItems().setAll("Classic", "Wide", "Vertical");
        layout.setValue(settings.layoutMode());
        listMode.getItems().setAll("Cards", "Table");
        listMode.setValue(settings.listMode());
        showNavigation.setSelected(settings.showNavigation());
        showReader.setSelected(settings.showReader());
        polyMailEnabled.setSelected(settings.polyMailEnabled());
        polyMailIdentity.setText(settings.polyMailIdentity());
        polyMailIdentity.setPromptText("you@graalemail");
        polyMailRpcUrls.setText(settings.polyMailRpcUrls());
        polyMailRpcUrls.setPromptText("https://rpc-amoy.polygon.technology, https://...");
        polyMailContract.setText(settings.polyMailContract());
        polyMailContract.setPromptText("0x…");
        polyMailChainId.setText(settings.polyMailChainId());
        polyMailNetwork.getItems().setAll("dev", "int", "test", "prod");
        polyMailNetwork.setValue(settings.polyMailNetwork());
        polyMailPort.getValueFactory().setValue(clamp(settings.polyMailListenPort(), 0, 65535));
        polyMailMailboxPeers.setText(settings.polyMailMailboxPeers());
        polyMailMailboxPeers.setPromptText("mailbox1.example.com:9000, mailbox2.example.com:9000");

        Label intervalLabel = new Label("Synchronise every (minutes)");
        intervalLabel.setLabelFor(syncInterval);
        Label pageLabel = new Label("Messages per page");
        pageLabel.setLabelFor(pageSize);
        syncInterval.setAccessibleText("Synchronisation interval in minutes");
        pageSize.setAccessibleText("Number of messages per page");
        density.setAccessibleText("Message list density");
        theme.setAccessibleText("Theme");
        layout.setAccessibleText("Workspace layout");
        listMode.setAccessibleText("Message list display");
        polyMailIdentity.setAccessibleText("PolyMail identity");
        polyMailRpcUrls.setAccessibleText("Polygon RPC endpoints, comma separated");
        polyMailContract.setAccessibleText("Notification contract address");
        polyMailChainId.setAccessibleText("Chain identifier");
        polyMailNetwork.setAccessibleText("PolyMail network");
        polyMailPort.setAccessibleText("Local P2P listen port, 0 for ephemeral");
        polyMailMailboxPeers.setAccessibleText("Reachable mailbox peers as host:port, comma separated");

        Button save = new Button("Save");
        save.setDefaultButton(true);
        save.setOnAction(event -> {
            int syncMinutes = readSpinner(syncInterval, 1, 240, settings.syncIntervalMinutes());
            int page = readSpinner(pageSize, 20, 1000, settings.pageSize());
            int listenPort = readSpinner(polyMailPort, 0, 65535, settings.polyMailListenPort());
            settings.syncIntervalMinutes(syncMinutes);
            settings.pageSize(page);
            settings.notificationsEnabled(notifications.isSelected());
            settings.compactDensity("Compact".equals(density.getValue()));
            settings.theme(theme.getValue());
            settings.layoutMode(layout.getValue());
            settings.listMode(listMode.getValue());
            settings.showNavigation(showNavigation.isSelected());
            settings.showReader(showReader.isSelected());
            settings.polyMailEnabled(polyMailEnabled.isSelected());
            settings.polyMailIdentity(polyMailIdentity.getText());
            settings.polyMailRpcUrls(polyMailRpcUrls.getText());
            settings.polyMailContract(polyMailContract.getText());
            settings.polyMailChainId(polyMailChainId.getText());
            settings.polyMailNetwork(polyMailNetwork.getValue());
            settings.polyMailListenPort(listenPort);
            try {
                com.graalmail.p2p.PeerAddress.parseList(polyMailMailboxPeers.getText());
            } catch (IllegalArgumentException e) {
                LOG.warn("Invalid mailbox peers: {}", e.getMessage());
                polyMailMailboxPeers.requestFocus();
                return;
            }
            settings.polyMailMailboxPeers(polyMailMailboxPeers.getText());
            LOG.info("Settings saved: sync every {} minutes, {} messages per page",
                    syncMinutes, page);
            stage.close();
        });
        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        cancel.setOnAction(event -> stage.close());

        javafx.scene.layout.HBox buttons = new javafx.scene.layout.HBox(UiScale.px(stage, 8), cancel, save);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        javafx.scene.layout.VBox syncSettings = new javafx.scene.layout.VBox(UiScale.px(stage, 10),
                intervalLabel, syncInterval, pageLabel, pageSize, notifications);
        syncSettings.setFillWidth(true);
        syncInterval.setMaxWidth(Double.MAX_VALUE);
        pageSize.setMaxWidth(Double.MAX_VALUE);
        javafx.scene.layout.VBox appearanceSettings = new javafx.scene.layout.VBox(UiScale.px(stage, 10),
                new Label("Message list display"), listMode,
                new Label("Message list density"), density, new Label("Theme"), theme);
        Label layoutHelp = new Label("Classic places the reader below the message list. Wide places it below the folder and list panes. Vertical shows all three panes side by side.");
        layoutHelp.setWrapText(true);
        javafx.scene.layout.VBox layoutSettings = new javafx.scene.layout.VBox(UiScale.px(stage, 10),
                new Label("Workspace layout"), layout, layoutHelp,
                showNavigation, showReader,
                new Label("You can also change pane visibility from the Layout menu in the main toolbar."));
        javafx.scene.layout.VBox polyMailSettings = buildPolyMailPane();
        javafx.scene.control.Tab syncTab = new javafx.scene.control.Tab("Sync", syncSettings);
        javafx.scene.control.Tab appearanceTab = new javafx.scene.control.Tab("Appearance", appearanceSettings);
        javafx.scene.control.Tab layoutTab = new javafx.scene.control.Tab("Layout", layoutSettings);
        javafx.scene.control.Tab polyMailTab = new javafx.scene.control.Tab("PolyMail", polyMailSettings);
        syncTab.setClosable(false);
        appearanceTab.setClosable(false);
        layoutTab.setClosable(false);
        polyMailTab.setClosable(false);
        javafx.scene.control.TabPane tabs = new javafx.scene.control.TabPane(syncTab, appearanceTab, layoutTab, polyMailTab);
        Label heading = new Label("GraalMail Settings");
        heading.getStyleClass().add("compose-heading");
        Label explanation = new Label("Choose how GraalMail looks, syncs, and arranges your workspace.");
        explanation.getStyleClass().add("settings-explanation");
        javafx.scene.layout.VBox root = new javafx.scene.layout.VBox(UiScale.px(stage, 10), heading, explanation, tabs, buttons);
        root.getStyleClass().add("account-setup");
        if (darkTheme) root.getStyleClass().add("dark-theme");
        root.setPadding(UiScale.insets(stage, 14));

        Scene scene = new Scene(root);
        var stylesheet = getClass().getResource("/styles/mail.css");
        if (stylesheet != null) scene.getStylesheets().add(stylesheet.toExternalForm());
        stage.setScene(scene);
        UiScale.configure(stage, scene, stage.getOwner(), 900, 650, 600, 420);
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

    private javafx.scene.layout.VBox buildPolyMailPane() {
        Label identityLabel = new Label("PolyMail identity");
        identityLabel.setLabelFor(polyMailIdentity);
        Label rpcLabel = new Label("Polygon RPC endpoints (comma separated)");
        rpcLabel.setLabelFor(polyMailRpcUrls);
        Label contractLabel = new Label("Notification contract address");
        contractLabel.setLabelFor(polyMailContract);
        Label chainLabel = new Label("Chain id");
        chainLabel.setLabelFor(polyMailChainId);
        Label networkLabel = new Label("Network");
        networkLabel.setLabelFor(polyMailNetwork);
        Label portLabel = new Label("Listen port (0 = ephemeral)");
        portLabel.setLabelFor(polyMailPort);
        Label peersLabel = new Label("Mailbox peers (host:port, comma separated)");
        peersLabel.setLabelFor(polyMailMailboxPeers);
        Label help = new Label("Decentralized delivery over Polygon notifications plus P2P content. "
                + "Configure at least one RPC endpoint and the contract address, then restart "
                + "or unlock the wallet to start the node. Keys stay in the platform key store; "
                + "only tags, message ids, hashes and timestamps go on chain.");
        help.setWrapText(true);
        polyMailRpcUrls.setPromptText("https://rpc-amoy.polygon.technology, https://...");
        javafx.scene.layout.VBox pane = new javafx.scene.layout.VBox(UiScale.px(stage, 10),
                polyMailEnabled, identityLabel, polyMailIdentity,
                rpcLabel, polyMailRpcUrls, contractLabel, polyMailContract,
                chainLabel, polyMailChainId, networkLabel, polyMailNetwork,
                portLabel, polyMailPort, peersLabel, polyMailMailboxPeers, help);
        pane.setFillWidth(true);
        polyMailIdentity.setMaxWidth(Double.MAX_VALUE);
        polyMailRpcUrls.setMaxWidth(Double.MAX_VALUE);
        polyMailContract.setMaxWidth(Double.MAX_VALUE);
        polyMailChainId.setMaxWidth(Double.MAX_VALUE);
        polyMailNetwork.setMaxWidth(Double.MAX_VALUE);
        polyMailPort.setMaxWidth(Double.MAX_VALUE);
        polyMailMailboxPeers.setMaxWidth(Double.MAX_VALUE);
        return pane;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void commitSpinnerOnFocusLoss(Spinner<Integer> spinner, int min, int max) {
        spinner.focusedProperty().addListener((observable, old, focused) -> {
            if (!focused) {
                readSpinner(spinner, min, max, clamp(spinner.getValue(), min, max));
            }
        });
        spinner.getEditor().setOnAction(event -> readSpinner(spinner, min, max, spinner.getValue()));
    }

    private static int readSpinner(Spinner<Integer> spinner, int min, int max, int fallback) {
        try {
            spinner.getValueFactory().setValue(Integer.parseInt(spinner.getEditor().getText().trim()));
        } catch (RuntimeException e) {
            // Keep the current spinner value; the editor text was not a number.
        }
        int value = spinner.getValue() == null ? fallback : spinner.getValue();
        int clamped = clamp(value, min, max);
        spinner.getValueFactory().setValue(clamped);
        spinner.getEditor().setText(Integer.toString(clamped));
        return clamped;
    }
}
