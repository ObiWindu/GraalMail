/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import com.graalmail.core.domain.Account;
import com.graalmail.core.error.ProviderException;
import com.graalmail.core.util.Log;
import com.graalmail.provider.api.AccountProvider;
import com.graalmail.provider.api.ProviderCapabilities;
import com.graalmail.provider.api.ProviderRegistry;
import com.graalmail.provider.api.ProviderSession;
import com.graalmail.provider.api.ProviderType;
import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;

/**
 * The diagnostics window of the desktop application.
 *
 * <p>The window shows the state of the local installation, the providers that
 * were found on the class path and the result of a connection test for every
 * configured account. The report can be copied into the clipboard so that it
 * can be attached to a bug report.</p>
 */
public final class DiagnosticsDialog {

    private static final Logger LOG = Log.of(DiagnosticsDialog.class);

    private final DesktopContext context;
    private final boolean darkTheme;
    private final Stage stage = new Stage();
    private final TextArea report = new TextArea();

    /**
     * Creates the diagnostics window.
     *
     * @param context the application context
     */
    public DiagnosticsDialog(DesktopContext context, boolean darkTheme) {
        this.context = context;
        this.darkTheme = darkTheme;
        stage.setTitle("Diagnostics");
    }

    /**
     * Builds the scene of the window.
     *
     * @return the scene
     */
    public Scene build() {
        report.setEditable(false);

        Button refresh = new Button("Run diagnostics");
        refresh.setOnAction(event -> runDiagnostics());
        Button copy = new Button("Copy report");
        copy.setOnAction(event -> {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(report.getText());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            status("The report was copied to the clipboard.");
        });
        Button close = new Button("Close");
        close.setOnAction(event -> stage.close());

        HBox buttons = new HBox(UiScale.px(stage, 8), refresh, copy, close);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(UiScale.px(stage, 10), report, buttons);
        root.getStyleClass().add("diagnostics-window");
        if (darkTheme) root.getStyleClass().add("dark-theme");
        root.setPadding(UiScale.insets(stage, 12));
        Scene scene = new Scene(root);
        stage.setScene(scene);
        UiScale.configure(stage, scene, stage.getOwner(), 760, 520, 500, 340);
        return stage.getScene();
    }

    /**
     * Shows the window and runs the diagnostics once.
     *
     * @param owner the owning window, may be {@code null}
     */
    public void show(Window owner) {
        if (stage.getScene() == null) {
            build();
        }
        if (owner != null) {
            try {
                stage.initOwner(owner);
                stage.initModality(Modality.WINDOW_MODAL);
            } catch (IllegalStateException e) {
                LOG.warn("The diagnostics window cannot be attached: {}", e.getMessage());
            }
        }
        runDiagnostics();
        stage.show();
    }

    private void runDiagnostics() {
        StringBuilder text = new StringBuilder();
        text.append("GraalMail diagnostics\n");
        text.append("generated: ").append(java.time.Instant.now()).append('\n');
        text.append("java: ").append(System.getProperty("java.version"))
                .append(" (").append(System.getProperty("java.vendor")).append(")\n");
        text.append("os: ").append(System.getProperty("os.name"))
                .append(' ').append(System.getProperty("os.version"))
                .append(" / ").append(System.getProperty("os.arch")).append('\n');
        text.append("data directory: ").append(context.paths().baseDirectory()).append('\n');
        text.append("database: ").append(context.databaseFile()).append('\n');
        text.append('\n');

        text.append("providers\n");
        List<AccountProvider> providers = ProviderRegistry.loadAll();
        if (providers.isEmpty()) {
            text.append("  no provider implementation was found on the class path\n");
        }
        for (AccountProvider provider : providers) {
            ProviderCapabilities capabilities = provider.capabilities();
            text.append("  - ").append(provider.displayName())
                    .append(" [").append(provider.type().id()).append("]\n");
            text.append("      authentication: ").append(capabilities.authMethods()).append('\n');
            text.append("      search: ").append(capabilities.serverSideSearch() ? "server" : "local")
                    .append(", labels: ").append(capabilities.labels())
                    .append(", attachments: ").append(capabilities.attachments())
                    .append(", sending: ").append(capabilities.sendCapable()).append('\n');
            text.append("      platforms: ").append(capabilities.platformSupport()).append('\n');
            for (String restriction : capabilities.restrictions()) {
                text.append("      restriction: ").append(restriction).append('\n');
            }
        }
        text.append('\n');

        text.append("accounts\n");
        List<Account> accounts = context.store().listAccounts();
        if (accounts.isEmpty()) {
            text.append("  no account is configured\n");
        }
        for (Account account : accounts) {
            text.append("  - ").append(account.email())
                    .append(" (").append(account.providerId()).append(")\n");
            text.append("      connection: ").append(testAccount(account)).append('\n');
        }

        report.setText(text.toString());
        report.positionCaret(0);
    }

    private String testAccount(Account account) {
        ProviderType type = ProviderType.fromId(account.providerId());
        try {
            ProviderSession session = context.sessions().open(account);
            try {
                int mailboxes = session.mailboxes().listFolders().size();
                return "ok, " + mailboxes + " mailbox(es)";
            } finally {
                session.close();
            }
        } catch (ProviderException e) {
            return "failed: " + e.getMessage();
        } catch (RuntimeException e) {
            LOG.warn("The connection test of {} failed: {}", account.email(), e.getMessage());
            return "failed: " + e.getMessage();
        }
    }

    private void status(String message) {
        LOG.info(message);
    }
}
