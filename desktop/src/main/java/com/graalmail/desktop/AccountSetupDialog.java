/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import com.graalmail.core.domain.Account;
import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import com.graalmail.core.util.Ids;
import com.graalmail.core.util.Log;
import com.graalmail.provider.api.AccountProvider;
import com.graalmail.provider.api.AccountSetup;
import com.graalmail.provider.api.AuthMethod;
import com.graalmail.provider.api.ProviderRegistry;
import com.graalmail.provider.api.ProviderType;
import com.graalmail.provider.api.ProviderDeduction;
import com.graalmail.provider.imap.ImapSettings;
import com.graalmail.provider.imap.SecurityMode;
import com.graalmail.provider.proton.ProtonApiSession;
import com.graalmail.provider.proton.ProtonProvider;
import com.graalmail.provider.proton.ProtonTransportMode;
import com.graalmail.provider.proton.api.DefaultProtonHttpTransport;
import com.graalmail.provider.proton.api.ProtonApiClient;
import com.graalmail.provider.proton.api.ProtonApiException;
import com.graalmail.provider.proton.api.ProtonAuth;
import com.graalmail.security.model.Credential;
import com.graalmail.security.model.TokenSet;
import com.graalmail.sync.AccountSessionFactory;
import java.awt.Desktop;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import javafx.geometry.Insets;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.Node;
import javafx.stage.Stage;
import org.slf4j.Logger;

/**
 * The guided account setup.
 *
 * <p>The dialog adapts itself to the selected provider: a manual server
 * configuration for generic IMAP accounts, an app password for Yahoo and an
 * OAuth 2.0 browser flow for Gmail, Microsoft and Yahoo OAuth accounts.</p>
 */
public final class AccountSetupDialog {

    private static final Logger LOG = Log.of(AccountSetupDialog.class);

    private final DesktopContext context;
    private final Stage stage;
    private Stage mainOwner;

    private final ComboBox<ProviderType> providerChoice = new ComboBox<>();
    private final Label detectedProviderLabel = new Label("Detected: Generic");
    private boolean providerManuallyChosen;
    private boolean adjustingProviderChoice;
    private final TextField emailField = new TextField();
    private final TextField displayNameField = new TextField();
    private final TextField imapHostField = new TextField("imap.example.com");
    private final TextField imapPortField = new TextField("993");
    private final ComboBox<String> imapSecurityField = new ComboBox<>(
            javafx.collections.FXCollections.observableArrayList("ssl", "starttls", "none"));
    private final TextField smtpHostField = new TextField("smtp.example.com");
    private final TextField smtpPortField = new TextField("587");
    private final ComboBox<String> smtpSecurityField = new ComboBox<>(
            javafx.collections.FXCollections.observableArrayList("starttls", "ssl", "none"));
    private final PasswordField passwordField = new PasswordField();
    private final PasswordField mailboxPasswordField = new PasswordField();
    private final TextField passwordVisibleField = new TextField();
    private final TextField mailboxVisibleField = new TextField();
    private final javafx.scene.control.CheckBox showPasswordsBox = new javafx.scene.control.CheckBox("Show passwords");
    private final TextField twoFactorField = new TextField();
    private boolean pendingTwoFactor;
    private boolean pendingCaptcha;
    private Button protonBrowserButton;
    private Button protonVerifyButton;
    private ProtonVerificationBrowser verificationBrowser;
    private final java.util.concurrent.atomic.AtomicBoolean verificationRetryArmed =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final Label status = new Label("");
    private final Label helpLabel = new Label(
            "Choose your provider and follow the sign-in steps. Your mail stays in GraalMail's local cache.");
    private final Label signinSectionLabel = new Label("Sign-in details");
    private final Button saveButton = new Button("Save account");
    private final Map<Node, Label> rowLabels = new java.util.HashMap<>();

    /**
     * Creates the dialog.
     *
     * @param context the application context
     * @param stage the parent stage
     */
    public AccountSetupDialog(DesktopContext context, Stage stage) {
        this.context = context;
        this.stage = stage;
        imapSecurityField.setValue("ssl");
        smtpSecurityField.setValue("starttls");
        status.setWrapText(true);
        status.getStyleClass().add("form-status");
        status.setAccessibleText("Form status");
    }

    /**
     * Shows the dialog and blocks until it is closed.
     *
     * <p>Everything lives on one screen: the account block, then the
     * sign-in block whose rows appear or hide with the email domain. The
     * window is sized to its content.</p>
     *
     * @param owner the owning window, may be {@code null}
     */
    public void showAndWait(Stage owner) {
        this.mainOwner = owner;
        providerChoice.getItems().setAll(ProviderType.values());
        providerChoice.setValue(ProviderType.GENERIC);
        providerChoice.valueProperty().addListener((observable, old, value) -> {
            if (!adjustingProviderChoice) {
                providerManuallyChosen = true;
            }
            refreshDetectedLabel();
            updateVisibility(value);
        });
        providerManuallyChosen = false;
        refreshDetectedLabel();

        GridPane identity = new GridPane();
        GridPane connection = new GridPane();
        for (GridPane grid : new GridPane[]{identity, connection}) {
            grid.setHgap(UiScale.px(stage, 10));
            grid.setVgap(UiScale.px(stage, 8));
            grid.setPadding(UiScale.insets(stage, 12));
            javafx.scene.layout.ColumnConstraints labelCol = new javafx.scene.layout.ColumnConstraints();
            labelCol.setMinWidth(UiScale.px(stage, 150));
            labelCol.setHgrow(javafx.scene.layout.Priority.NEVER);
            javafx.scene.layout.ColumnConstraints fieldCol = new javafx.scene.layout.ColumnConstraints();
            fieldCol.setHgrow(javafx.scene.layout.Priority.ALWAYS);
            fieldCol.setMinWidth(UiScale.px(stage, 220));
            grid.getColumnConstraints().setAll(labelCol, fieldCol);
        }
        addRow(identity, 0, "Provider", providerCell());
        addRow(identity, 1, "Email", emailField);
        addRow(identity, 2, "Display name", displayNameField);
        emailField.textProperty().addListener((observable, old, value) -> {
            providerManuallyChosen = false;
            ProviderType deduced = ProviderDeduction.deduceProvider(value);
            if (deduced != providerChoice.getValue()) {
                adjustingProviderChoice = true;
                try {
                    providerChoice.setValue(deduced);
                } finally {
                    adjustingProviderChoice = false;
                }
            }
            refreshDetectedLabel();
        });
        int row = 0;
        row = addRow(connection, row, "IMAP server", imapHostField);
        row = addRow(connection, row, "IMAP port", imapPortField);
        row = addRow(connection, row, "IMAP security", imapSecurityField);
        row = addRow(connection, row, "SMTP server", smtpHostField);
        row = addRow(connection, row, "SMTP port", smtpPortField);
        row = addRow(connection, row, "SMTP security", smtpSecurityField);
        row = addRow(connection, row, "Password or app password", passwordField);
        row = addRow(connection, row, "", passwordVisibleField);
        row = addRow(connection, row, "Mailbox password (two-password mode only)", mailboxPasswordField);
        row = addRow(connection, row, "", mailboxVisibleField);
        row = addRow(connection, row, "Two-factor code (if asked)", twoFactorField);
        row = addRow(connection, row, "", showPasswordsBox);
        passwordVisibleField.setVisible(false);
        passwordVisibleField.setManaged(false);
        mailboxVisibleField.setVisible(false);
        mailboxVisibleField.setManaged(false);
        syncPasswordVisibility(passwordField, passwordVisibleField);
        syncPasswordVisibility(mailboxPasswordField, mailboxVisibleField);
        showPasswordsBox.setAccessibleText("Show passwords in plain text");
        showPasswordsBox.setTooltip(new javafx.scene.control.Tooltip("Reveal the entered passwords"));
        showPasswordsBox.selectedProperty().addListener((observable, old, show) -> {
            String login = show ? passwordField.getText() : passwordVisibleField.getText();
            String mailbox = show ? mailboxPasswordField.getText() : mailboxVisibleField.getText();
            passwordVisibleField.setText(login == null ? "" : login);
            mailboxVisibleField.setText(mailbox == null ? "" : mailbox);
            passwordField.setVisible(!show);
            passwordField.setManaged(!show);
            passwordVisibleField.setVisible(show);
            passwordVisibleField.setManaged(show);
            mailboxPasswordField.setVisible(!show && isMailboxRowNeeded());
            mailboxPasswordField.setManaged(!show && isMailboxRowNeeded());
            mailboxVisibleField.setVisible(show && isMailboxRowNeeded());
            mailboxVisibleField.setManaged(show && isMailboxRowNeeded());
            updateVisibility(providerChoice.getValue() == null
                    ? ProviderType.GENERIC : providerChoice.getValue());
        });
        passwordVisibleField.setPromptText(passwordField.getPromptText());
        mailboxVisibleField.setPromptText(mailboxPasswordField.getPromptText());
        emailField.textProperty().addListener((observable, old, value) ->
                updateVisibility(providerChoice.getValue() == null
                        ? ProviderType.GENERIC : providerChoice.getValue()));

        protonBrowserButton = new Button("Open Proton login in browser");
        protonBrowserButton.setOnAction(event -> openProtonLoginPage());
        protonBrowserButton.setVisible(false);
        protonBrowserButton.setManaged(false);
        protonVerifyButton = new Button("Verify in private window");
        protonVerifyButton.setOnAction(event -> confirmVerificationWindow());
        protonVerifyButton.setVisible(false);
        protonVerifyButton.setManaged(false);
        Button cancel = new Button("Cancel");
        cancel.setOnAction(event -> stage.close());
        saveButton.setDefaultButton(true);
        saveButton.setOnAction(event -> save());

        javafx.scene.layout.FlowPane buttons = new javafx.scene.layout.FlowPane(
                UiScale.px(stage, 8), UiScale.px(stage, 6),
                protonVerifyButton, protonBrowserButton, saveButton, cancel);
        buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);

        Label heading = new Label("Add an account");
        heading.getStyleClass().add("compose-heading");
        helpLabel.setWrapText(true);
        Label accountSection = new Label("Account");
        accountSection.getStyleClass().add("pane-heading");
        signinSectionLabel.getStyleClass().add("pane-heading");
        VBox content = new VBox(UiScale.px(stage, 12), heading, helpLabel,
                accountSection, identity, signinSectionLabel, connection, status, buttons);
        content.getStyleClass().add("account-setup");
        boolean dark = new DesktopSettings(context.store()).darkTheme();
        if (dark) content.getStyleClass().add("dark-theme");
        content.setPadding(UiScale.insets(stage, 16));
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        if (dark) scroll.getStyleClass().add("dark-theme");
        scroll.setStyle(dark ? "-fx-background: #2A2E32; -fx-background-color: #2A2E32;" : "");
        javafx.scene.Scene scene = new javafx.scene.Scene(scroll);
        var stylesheet = getClass().getResource("/styles/mail.css");
        if (stylesheet != null) scene.getStylesheets().add(stylesheet.toExternalForm());
        stage.setScene(scene);
        if (owner != null) {
            stage.initOwner(owner);
            stage.initModality(javafx.stage.Modality.WINDOW_MODAL);
        }
        stage.setOnHiding(event -> closeVerificationBrowser());
        UiScale.configure(stage, scene, owner != null ? owner : stage, 680, 780, 520, 460);
        updateVisibility(providerChoice.getValue() == null
                ? ProviderType.GENERIC : providerChoice.getValue());
        Platform.runLater(emailField::requestFocus);
        stage.showAndWait();
    }

    /**
     * The provider cell: the deduced provider with a manual override that
     * stays hidden unless the deduction is wrong (for example a custom
     * domain hosted by Gmail, Microsoft 365 or Proton).
     */
    private VBox providerCell() {
        javafx.scene.control.Hyperlink change =
                new javafx.scene.control.Hyperlink("Change");
        change.setOnAction(event -> {
            providerChoice.setVisible(true);
            providerChoice.setManaged(true);
            providerChoice.requestFocus();
        });
        HBox detected = new HBox(UiScale.px(stage, 8), detectedProviderLabel, change);
        detected.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        providerChoice.setVisible(false);
        providerChoice.setManaged(false);
        providerChoice.setMaxWidth(Double.MAX_VALUE);
        return new VBox(UiScale.px(stage, 4), detected, providerChoice);
    }

    private void refreshDetectedLabel() {
        ProviderType type = providerChoice.getValue();
        String name = type == null ? "Generic" : type.displayName();
        detectedProviderLabel.setText(providerManuallyChosen
                ? "Provider: " + name + " (manual)" : "Detected: " + name);
    }

    private int addRow(GridPane grid, int row, String label, javafx.scene.Node node) {
        Label caption = new Label(label);
        caption.setLabelFor(node);
        rowLabels.put(node, caption);
        grid.add(caption, 0, row);
        grid.add(node, 1, row);
        javafx.scene.layout.GridPane.setHgrow(node, javafx.scene.layout.Priority.ALWAYS);
        if (node instanceof javafx.scene.control.TextInputControl input) {
            input.setMaxWidth(Double.MAX_VALUE);
        } else if (node instanceof ComboBox<?> combo) {
            combo.setMaxWidth(Double.MAX_VALUE);
        } else if (node instanceof VBox box) {
            box.setMaxWidth(Double.MAX_VALUE);
        }
        return row + 1;
    }

    private void updateVisibility(ProviderType type) {
        boolean generic = type == ProviderType.GENERIC;
        boolean proton = type == ProviderType.PROTON;
        if (!proton) {
            pendingTwoFactor = false;
            pendingCaptcha = false;
            closeVerificationBrowser();
        }
        if (protonBrowserButton != null) {
            boolean showBrowser = proton && pendingCaptcha;
            protonBrowserButton.setVisible(showBrowser);
            protonBrowserButton.setManaged(showBrowser);
        }
        if (protonVerifyButton != null) {
            boolean showVerify = proton && pendingCaptcha;
            protonVerifyButton.setVisible(showVerify);
            protonVerifyButton.setManaged(showVerify);
        }
        imapHostField.setDisable(!generic);
        imapPortField.setDisable(!generic);
        imapSecurityField.setDisable(!generic);
        smtpHostField.setDisable(!generic);
        smtpPortField.setDisable(!generic);
        smtpSecurityField.setDisable(!generic);
        boolean microsoft = type == ProviderType.MICROSOFT;
        boolean gmail = type == ProviderType.GMAIL;
        passwordField.setDisable(microsoft);
        setRowVisible(imapHostField, generic);
        setRowVisible(imapPortField, generic);
        setRowVisible(imapSecurityField, generic);
        setRowVisible(smtpHostField, generic);
        setRowVisible(smtpPortField, generic);
        setRowVisible(smtpSecurityField, generic);
        setRowVisible(passwordField, !microsoft);
        setRowVisible(mailboxPasswordField, proton);
        setRowVisible(twoFactorField, proton && pendingTwoFactor);
        signinSectionLabel.setText(proton ? "Proton sign-in"
                : generic ? "Server settings" : "Provider sign-in");
        Label passwordCaption = rowLabels.get(passwordField);
        if (passwordCaption != null) {
            passwordCaption.setText(proton ? "Proton password"
                    : gmail ? "App password" : "Password or app password");
        }
        if (proton) {
            helpLabel.setText("Sign in with the Proton username and password; no Bridge is needed. "
                    + "With two-password mode, also enter the mailbox password. "
                    + "Mail is decrypted locally on this machine.");
        } else if (gmail) {
            helpLabel.setText("GraalMail signs in with the email address and a password. "
                    + "Google rejects account passwords for mail clients: create an app password "
                    + "in the Google account security settings (this requires 2-Step Verification "
                    + "on the account) and enter it here. "
                    + "Your mail stays in GraalMail's local cache.");
        } else if (microsoft) {
            helpLabel.setText("GraalMail signs in with the email address and password only. "
                    + "Microsoft requires OAuth tokens for mail access, which this version no "
                    + "longer offers, so new Microsoft accounts cannot be added. Existing "
                    + "Microsoft accounts keep working.");
        } else {
            helpLabel.setText("Enter the email address and follow the sign-in steps. "
                    + "Your mail stays in GraalMail's local cache.");
        }
        refitDialog();
    }

    /**
     * Resizes the window to its content, clamped to the screen, so the
     * single screen always covers exactly its visible elements. Skipped
     * while hidden so headless use stays safe.
     */
    private void refitDialog() {
        if (!stage.isShowing()) {
            return;
        }
        stage.sizeToScene();
        javafx.stage.Window anchor = stage.getOwner() != null ? stage.getOwner() : stage;
        javafx.stage.Screen screen = javafx.stage.Screen.getPrimary();
        if (anchor.getWidth() > 0 && anchor.getHeight() > 0) {
            double centerX = anchor.getX() + anchor.getWidth() / 2;
            double centerY = anchor.getY() + anchor.getHeight() / 2;
            var screens = javafx.stage.Screen.getScreensForRectangle(centerX, centerY, 1, 1);
            if (!screens.isEmpty()) {
                screen = screens.getFirst();
            }
        }
        double maxWidth = screen.getVisualBounds().getWidth() * 0.92;
        double maxHeight = screen.getVisualBounds().getHeight() * 0.90;
        if (stage.getWidth() > maxWidth) {
            stage.setWidth(maxWidth);
        }
        if (stage.getHeight() > maxHeight) {
            stage.setHeight(maxHeight);
        }
    }

    private void setRowVisible(Node field, boolean visible) {        field.setVisible(visible);
        field.setManaged(visible);
        Label label = rowLabels.get(field);
        if (label != null) {
            label.setVisible(visible);
            label.setManaged(visible);
        }
    }

    private void openProtonLoginPage() {
        if (!Desktop.isDesktopSupported()
                || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            status.setText("No browser is available. Open "
                    + ProtonApiException.HUMAN_VERIFICATION_LOGIN_URL + " manually, "
                    + "sign in once, then press Save again.");
            return;
        }
        browseInBackground(URI.create(ProtonApiException.HUMAN_VERIFICATION_LOGIN_URL),
                "A browser window was opened. Complete the check there, "
                        + "then press Save again.",
                "The Proton login page could not be opened: ");
    }

    /**
     * Opens Proton's login page in a managed private Firefox window so the
     * human-verification check can be completed. Asks for explicit consent
     * first: the first run downloads Firefox (~90&nbsp;MB, kept for reuse).
     * Runs off the JavaFX thread: startup and a possible download block.
     */
    private void confirmVerificationWindow() {
        javafx.scene.control.Alert confirm = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Verify in private window");
        confirm.setHeaderText("Open Proton's login page in a private browser window?");
        confirm.setContentText("This downloads a private Firefox (~90 MB, one time, kept for "
                + "reuse) if none is cached yet. Complete the check there, then press Save again.");
        var download = new javafx.scene.control.ButtonType("Download & open",
                javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        var cancel = new javafx.scene.control.ButtonType("Cancel",
                javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        confirm.getButtonTypes().setAll(download, cancel);
        boolean maximized = UiScale.isMaximized(mainOwner);
        if (confirm.showAndWait().filter(button -> button == download).isEmpty()) {
            UiScale.restoreMaximized(mainOwner, maximized);
            return;
        }
        UiScale.restoreMaximized(mainOwner, maximized);
        openVerificationWindow();
    }

    private void openVerificationWindow() {
        status.setText("Opening verification window…");
        Thread worker = new Thread(() -> {
            try {
                if (verificationBrowser == null) {
                    verificationBrowser = new ProtonVerificationBrowser();
                }
                verificationBrowser.setDisconnectHandler(closed ->
                        autoRetryAfterVerification());
                verificationBrowser.open(ProtonApiException.HUMAN_VERIFICATION_LOGIN_URL);
                verificationRetryArmed.set(true);
                Platform.runLater(() -> status.setText(
                        "Complete the check in the opened window, then press Save again — "
                                + "or just close the window and the sign-in retries by itself."));
            } catch (Exception e) {
                LOG.warn("The verification window could not be opened: {}", e.getMessage());
                Platform.runLater(() -> status.setText(
                        "Could not open a verification window. Use “Open Proton login "
                                + "in browser” instead."));
            }
        }, "graalmail-verify");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Retries the sign-in once when the verification window is closed by the
     * user. Exactly-once via the armed flag: programmatic closes disarm
     * first, so only a user-initiated close retries. A retry is a single
     * login attempt; if the check is still pending the guidance reappears.
     */
    private void autoRetryAfterVerification() {
        if (!verificationRetryArmed.compareAndSet(true, false)) {
            return;
        }
        Platform.runLater(() -> {
            status.setText("Verification window closed — retrying sign-in…");
            save();
        });
    }

    /** Closes the managed verification window, if any, without blocking. */
    private void closeVerificationBrowser() {
        verificationRetryArmed.set(false);
        ProtonVerificationBrowser browser = verificationBrowser;
        verificationBrowser = null;
        if (browser == null) {
            return;
        }
        Thread worker = new Thread(browser::close, "graalmail-verify-close");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Opens a URI in the system browser without freezing the setup window.
     * {@code Desktop.browse} can block for a long time on some Linux
     * desktops, so it must never run on the JavaFX thread.
     */
    private void browseInBackground(URI uri, String openedText, String failurePrefix) {
        status.setText("Opening browser…");
        Thread worker = new Thread(() -> {
            try {
                Desktop.getDesktop().browse(uri);
                Platform.runLater(() -> status.setText(openedText));
            } catch (Exception e) {
                LOG.warn("The browser could not be opened: {}", e.getMessage());
                Platform.runLater(() -> status.setText(failurePrefix + e.getMessage()));
            }
        }, "graalmail-browse");
        worker.setDaemon(true);
        worker.start();
    }

    private void save() {
        ProviderType type = providerChoice.getValue();
        if (type == null) {
            status.setText("Select a provider first.");
            return;
        }
        if (type == ProviderType.PROTON) {
            saveProtonAsync();
            return;
        }
        AccountProvider provider = ProviderRegistry.find(type).orElse(null);
        if (provider == null) {
            status.setText("The provider implementation is not available in this build.");
            return;
        }
        try {
            AccountSetup setup = buildSetup(type, provider);
            persistAccount(setup);
        } catch (ProviderException e) {
            status.setText(e.userMessage());
            status.getStyleClass().remove("form-status-ok");
            if (!status.getStyleClass().contains("form-status-error")) {
                status.getStyleClass().add("form-status-error");
            }
        } catch (RuntimeException e) {
            LOG.warn("The account could not be saved: {}", e.getMessage());
            status.setText("The account could not be saved: " + e.getMessage());
        }
    }

    private void persistAccount(AccountSetup setup) {
        Account account = Account.builder(Ids.uuid(), providerChoice.getValue().id())
                .email(setup.email())
                .displayName(setup.displayName())
                .settings(setup.settings())
                .credentialRef(Ids.shortId())
                .build();
        context.store().saveAccount(account);
        AccountSessionFactory sessions = context.sessions();
        sessions.saveCredential(account, setup.credential());
        status.setText("The account was saved.");
        status.getStyleClass().remove("form-status-error");
        if (!status.getStyleClass().contains("form-status-ok")) {
            status.getStyleClass().add("form-status-ok");
        }
        closeVerificationBrowser();
        stage.close();
    }

    private void saveProtonAsync() {
        String email = emailField.getText() == null ? "" : emailField.getText().trim();
        String displayName = displayNameField.getText() == null ? "" : displayNameField.getText().trim();
        markInvalid(emailField, false);
        markInvalid(passwordField, false);
        markInvalid(passwordVisibleField, false);
        if (email.isBlank()) {
            markInvalid(emailField, true);
            status.setText("Enter the email address for this account first.");
            return;
        }
        if (!validEmail(email)) {
            markInvalid(emailField, true);
            status.setText("That email address does not look valid — check for typos.");
            return;
        }
        String loginPassword = enteredLoginPassword();
        if (loginPassword.isEmpty()) {
            markInvalid(showPasswordsBox.isSelected() ? passwordVisibleField : passwordField, true);
            status.setText("Enter the Proton password.");
            return;
        }
        saveButton.setDisable(true);
        status.setText("Signing in to Proton…");
        status.getStyleClass().removeAll("form-status-error", "form-status-ok");
        String mailboxPassword = enteredMailboxPassword().isEmpty()
                ? loginPassword : enteredMailboxPassword();
        String twoFactorCode = pendingTwoFactor && twoFactorField.getText() != null
                ? twoFactorField.getText().trim() : null;
        Thread worker = new Thread(() -> {
            try {
                AccountSetup setup = buildProtonSetupBlocking(email, displayName,
                        loginPassword, mailboxPassword, twoFactorCode);
                Platform.runLater(() -> {
                    saveButton.setDisable(false);
                    try {
                        persistAccount(setup);
                    } catch (RuntimeException e) {
                        LOG.warn("The account could not be saved: {}", e.getMessage());
                        status.setText("The account could not be saved: " + e.getMessage());
                    }
                });
            } catch (ProtonAuth.TwoFactorRequired e) {
                Platform.runLater(() -> {
                    saveButton.setDisable(false);
                    pendingTwoFactor = true;
                    updateVisibility(ProviderType.PROTON);
                    status.setText("Enter the two-factor code from the authenticator app and press Save again.");
                });
            } catch (ProtonApiException.CaptchaRequired e) {
                Platform.runLater(() -> {
                    saveButton.setDisable(false);
                    pendingCaptcha = true;
                    updateVisibility(ProviderType.PROTON);
                    status.setText(e.userMessage() == null ? e.getMessage() : e.userMessage());
                });
            } catch (ProviderException e) {
                Platform.runLater(() -> {
                    saveButton.setDisable(false);
                    if (e.code() == ErrorCode.CONSENT_REQUIRED
                            && e.getMessage() != null && e.getMessage().contains("two-factor")) {
                        pendingTwoFactor = true;
                        updateVisibility(ProviderType.PROTON);
                    }
                    status.setText(e.userMessage());
                });
            } catch (RuntimeException e) {
                LOG.warn("Proton sign in failed: {}", e.getMessage());
                Platform.runLater(() -> {
                    saveButton.setDisable(false);
                    status.setText("Proton could not be reached. Check the connection and retry.");
                });
            }
        }, "graalmail-proton-login");
        worker.setDaemon(true);
        worker.start();
    }

    private AccountSetup buildProtonSetupBlocking(String email, String displayName,
            String loginPassword, String mailboxPassword, String twoFactorCode) {
        ProtonAuth auth = new ProtonAuth(new DefaultProtonHttpTransport());
        ProtonAuth.Tokens tokens = auth.login(email, loginPassword, twoFactorCode);
        pendingTwoFactor = false;
        pendingCaptcha = false;
        java.util.Map<String, String> settings = new java.util.LinkedHashMap<>();
        settings.put(ProtonTransportMode.SETTING, ProtonTransportMode.API.settingValue());
        settings.put(ProtonProvider.SETTING_UID, tokens.uid());
        TokenSet tokenSet = new TokenSet(tokens.accessToken(),
                tokens.refreshToken(), tokens.expiresAt(), tokens.scope(), "", java.util.Map.of());
        Credential credential =
                new Credential(email, loginPassword, tokenSet, mailboxPassword);
        try (com.graalmail.provider.api.ProviderSession verify =
                     new ProtonApiSession(email,
                             new ProtonApiClient(new DefaultProtonHttpTransport(), auth, tokens),
                             mailboxPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8), email)) {
            ((ProtonApiSession) verify).unlock();
        } catch (RuntimeException e) {
            LOG.warn("Proton mailbox verification failed: {}", e.getMessage());
            throw new ProviderException(ErrorCode.AUTHENTICATION_FAILED,
                    "The Proton mailbox could not be opened. "
                            + "With two-password mode, enter the mailbox password too.", false, e);
        }
        return AccountSetup.builder(AuthMethod.PASSWORD)
                .email(email)
                .displayName(displayName)
                .credential(credential)
                .settings(settings)
                .build();
    }

    private AccountSetup buildSetup(ProviderType type, AccountProvider provider) {
        String email = emailField.getText() == null ? "" : emailField.getText().trim();
        String displayName = displayNameField.getText() == null ? "" : displayNameField.getText().trim();
        markInvalid(emailField, false);
        if (email.isBlank()) {
            markInvalid(emailField, true);
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "The email address is missing.", false);
        }
        if (!validEmail(email)) {
            markInvalid(emailField, true);
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "That email address does not look valid — check for typos.", false);
        }
        if (type == ProviderType.MICROSOFT) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "Microsoft does not accept password sign in for mail clients, and GraalMail "
                            + "signs in with the email address and password only, so new Microsoft "
                            + "accounts cannot be added. Existing Microsoft accounts keep working.",
                    false);
        }
        if (type == ProviderType.PROTON) {
            String loginPassword = enteredLoginPassword();
            String mailboxPassword = enteredMailboxPassword().isEmpty()
                    ? loginPassword : enteredMailboxPassword();
            String twoFactorCode = pendingTwoFactor && twoFactorField.getText() != null
                    ? twoFactorField.getText().trim() : null;
            return buildProtonSetupBlocking(email, displayName,
                    loginPassword, mailboxPassword, twoFactorCode);
        }
        if (type == ProviderType.GENERIC) {
            String imapHost = orDefault(imapHostField.getText(), "").trim();
            String smtpHost = orDefault(smtpHostField.getText(), "").trim();
            if (imapHost.isEmpty() || imapHost.equalsIgnoreCase("imap.example.com")) {
                throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                        "Enter the IMAP host of your provider (e.g. imap.example.org).", false);
            }
            if (smtpHost.isEmpty() || smtpHost.equalsIgnoreCase("smtp.example.com")) {
                throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                        "Enter the SMTP host of your provider (e.g. smtp.example.org).", false);
            }
            int imapPort = parsePort(imapPortField.getText(), "IMAP");
            int smtpPort = parsePort(smtpPortField.getText(), "SMTP");
            Map<String, String> settings = new LinkedHashMap<>();
            settings.put(ImapSettings.IMAP_HOST, imapHost);
            settings.put(ImapSettings.IMAP_PORT, Integer.toString(imapPort));
            settings.put(ImapSettings.IMAP_SECURITY, orDefault(imapSecurityField.getValue(), "ssl"));
            settings.put(ImapSettings.SMTP_HOST, smtpHost);
            settings.put(ImapSettings.SMTP_PORT, Integer.toString(smtpPort));
            settings.put(ImapSettings.SMTP_SECURITY, orDefault(smtpSecurityField.getValue(), "starttls"));
            settings.put(ImapSettings.USERNAME, email);
            return AccountSetup.builder(AuthMethod.PASSWORD)
                    .email(email)
                    .displayName(displayName)
                    .password(email, enteredLoginPassword())
                    .settings(settings)
                    .build();
        }
        AuthMethod method = provider.capabilities().supportsAuthMethod(AuthMethod.APP_PASSWORD)
                ? AuthMethod.APP_PASSWORD : AuthMethod.PASSWORD;
        return AccountSetup.builder(method)
                .email(email)
                .displayName(displayName)
                .password(email, enteredLoginPassword())
                .build();
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void syncPasswordVisibility(PasswordField hidden, TextField visible) {
        boolean[] guard = {false};
        hidden.textProperty().addListener((observable, old, value) -> {
            if (guard[0]) return;
            guard[0] = true;
            try {
                if (!visible.getText().equals(value == null ? "" : value)) {
                    visible.setText(value == null ? "" : value);
                }
            } finally {
                guard[0] = false;
            }
        });
        visible.textProperty().addListener((observable, old, value) -> {
            if (guard[0]) return;
            guard[0] = true;
            try {
                if (!hidden.getText().equals(value == null ? "" : value)) {
                    hidden.setText(value == null ? "" : value);
                }
            } finally {
                guard[0] = false;
            }
        });
    }

    private boolean isMailboxRowNeeded() {
        ProviderType type = providerChoice.getValue();
        return type == ProviderType.PROTON;
    }

    private String enteredLoginPassword() {
        String value = passwordVisibleField.isVisible()
                ? passwordVisibleField.getText() : passwordField.getText();
        return value == null ? "" : value;
    }

    private String enteredMailboxPassword() {
        String value = mailboxVisibleField.isVisible()
                ? mailboxVisibleField.getText() : mailboxPasswordField.getText();
        return value == null ? "" : value;
    }

    private static void markInvalid(javafx.scene.Node field, boolean invalid) {
        if (invalid) {
            if (!field.getStyleClass().contains("compose-field-error")) {
                field.getStyleClass().add("compose-field-error");
            }
        } else {
            field.getStyleClass().remove("compose-field-error");
        }
    }

    private static boolean validEmail(String value) {
        return value != null && value.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    }

    private static int parsePort(String raw, String label) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "Enter the " + label + " port (e.g. 993 for IMAP).", false);
        }
        try {
            int port = Integer.parseInt(text);
            if (port <= 0 || port > 65535) {
                throw new NumberFormatException("out of range");
            }
            return port;
        } catch (NumberFormatException e) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "The " + label + " port must be a number between 1 and 65535.", false, e);
        }
    }

    /**
     * The credential that was entered for a manual setup.
     *
     * @return the credential, never {@code null}
     */
    public Credential enteredCredential() {
        return Credential.password(emailField.getText() == null ? "" : emailField.getText(),
                enteredLoginPassword());
    }

    /**
     * The security mode selected for the IMAP connection.
     *
     * @return the mode, {@link SecurityMode#SSL_TLS} by default
     */
    public SecurityMode imapSecurityMode() {
        String value = imapSecurityField.getValue();
        if (value == null || value.isBlank()) {
            return SecurityMode.SSL_TLS;
        }
        try {
            return SecurityMode.parse(value);
        } catch (RuntimeException e) {
            return SecurityMode.SSL_TLS;
        }
    }
}
