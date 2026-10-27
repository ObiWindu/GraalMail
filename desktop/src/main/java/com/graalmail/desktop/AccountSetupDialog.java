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
import com.graalmail.provider.api.OAuthCapableProvider;
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
import com.graalmail.security.oauth.AuthorizationRequest;
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
    private final TextField twoFactorField = new TextField();
    private boolean pendingTwoFactor;
    private boolean pendingCaptcha;
    private Button openBrowserButton;
    private Button protonBrowserButton;
    private Button protonVerifyButton;
    private ProtonVerificationBrowser verificationBrowser;
    private final java.util.concurrent.atomic.AtomicBoolean verificationRetryArmed =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final TextField clientIdField = new TextField();
    private final TextField redirectUriField = new TextField("http://localhost:8080/oauth/callback");
    private final TextField redirectResultField = new TextField();
    private final Label status = new Label("");
    private final Label helpLabel = new Label(
            "Choose your provider and follow the sign-in steps. Your mail stays in GraalMail's local cache.");
    private final Label signinSectionLabel = new Label("Sign-in details");
    private final Button saveButton = new Button("Save account");
    private final Map<Node, Label> rowLabels = new java.util.HashMap<>();

    private AuthorizationRequest pendingAuthorization;
    private AccountSetup pendingSetup;

    /**
     * Creates the dialog.
     *
     * @param context the application context
     * @param stage the parent stage
     */
    public AccountSetupDialog(DesktopContext context, Stage stage) {
        this.context = context;
        this.stage = stage;
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
        row = addRow(connection, row, "Mailbox password (two-password mode only)", mailboxPasswordField);
        row = addRow(connection, row, "Two-factor code (if asked)", twoFactorField);
        row = addRow(connection, row, "OAuth client id", clientIdField);
        row = addRow(connection, row, "OAuth redirect URI", redirectUriField);
        row = addRow(connection, row, "Redirect result URL", redirectResultField);
        clientIdField.textProperty().addListener((observable, old, value) ->
                updateVisibility(providerChoice.getValue()));

        Button openBrowser = new Button("Open sign in page");
        openBrowser.setOnAction(event -> openAuthorizationPage());
        openBrowserButton = openBrowser;
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
                openBrowser, protonVerifyButton, protonBrowserButton, saveButton, cancel);
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
        if (new DesktopSettings(context.store()).darkTheme()) content.getStyleClass().add("dark-theme");
        content.setPadding(UiScale.insets(stage, 16));
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
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

    private boolean requiresOAuth() {
        ProviderType type = providerChoice.getValue();
        return type == ProviderType.GMAIL || type == ProviderType.MICROSOFT
                || (type == ProviderType.YAHOO && clientIdField.getText() != null
                && !clientIdField.getText().isBlank());
    }

    private int addRow(GridPane grid, int row, String label, javafx.scene.Node node) {
        Label caption = new Label(label);
        rowLabels.put(node, caption);
        grid.add(caption, 0, row);
        grid.add(node, 1, row);
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
        boolean oauth = requiresOAuth();
        imapHostField.setDisable(!generic);
        imapPortField.setDisable(!generic);
        imapSecurityField.setDisable(!generic);
        smtpHostField.setDisable(!generic);
        smtpPortField.setDisable(!generic);
        smtpSecurityField.setDisable(!generic);
        passwordField.setDisable(type == ProviderType.GMAIL || type == ProviderType.MICROSOFT);
        clientIdField.setDisable(type == ProviderType.GENERIC || proton);
        redirectUriField.setDisable(type == ProviderType.GENERIC || proton);
        redirectResultField.setDisable(type == ProviderType.GENERIC || proton);
        setRowVisible(imapHostField, generic);
        setRowVisible(imapPortField, generic);
        setRowVisible(imapSecurityField, generic);
        setRowVisible(smtpHostField, generic);
        setRowVisible(smtpPortField, generic);
        setRowVisible(smtpSecurityField, generic);
        setRowVisible(passwordField, type != ProviderType.GMAIL && type != ProviderType.MICROSOFT);
        setRowVisible(mailboxPasswordField, proton);
        setRowVisible(twoFactorField, proton && pendingTwoFactor);
        setRowVisible(clientIdField, type != ProviderType.GENERIC && !proton);
        setRowVisible(redirectUriField, type != ProviderType.GENERIC && !proton);
        setRowVisible(redirectResultField, oauth);
        if (openBrowserButton != null) {
            openBrowserButton.setVisible(oauth);
            openBrowserButton.setManaged(oauth);
        }
        signinSectionLabel.setText(proton ? "Proton sign-in"
                : generic ? "Server settings" : "Provider sign-in");
        Label passwordCaption = rowLabels.get(passwordField);
        if (passwordCaption != null) {
            passwordCaption.setText(proton ? "Proton password" : "Password or app password");
        }
        if (proton) {
            helpLabel.setText("Sign in with the Proton username and password; no Bridge is needed. "
                    + "With two-password mode, also enter the mailbox password. "
                    + "Mail is decrypted locally on this machine.");
        } else if (oauth) {
            helpLabel.setText("Press “Open sign in page”, finish signing in in the browser, "
                    + "paste the redirect URL below, then press Save. "
                    + "Your mail stays in GraalMail's local cache.");
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

    private void openAuthorizationPage() {
        ProviderType type = providerChoice.getValue();
        if (type == null) {
            return;
        }
        AccountProvider provider = ProviderRegistry.find(type).orElse(null);
        if (!(provider instanceof OAuthCapableProvider oauth)) {
            status.setText("This provider does not use an OAuth sign in.");
            return;
        }
        try {
            pendingSetup = AccountSetup.builder(AuthMethod.OAUTH2)
                    .email(emailField.getText() == null ? "" : emailField.getText().trim())
                    .displayName(displayNameField.getText())
                    .setting("oauth.clientId", clientIdField.getText() == null ? "" : clientIdField.getText().trim())
                    .setting("oauth.redirectUri", redirectUriField.getText() == null ? "" : redirectUriField.getText().trim())
                    .build();
            pendingAuthorization = oauth.beginAuthorization(pendingSetup);
            browseInBackground(URI.create(pendingAuthorization.authorizationUrl()),
                    "A browser window was opened. Paste the full redirect URL into the "
                            + "redirect field and press Save.",
                    "The sign in page could not be opened: ");
        } catch (Exception e) {
            LOG.warn("The authorization page could not be opened: {}", e.getMessage());
            status.setText("The sign in page could not be opened: " + e.getMessage());
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
        if (confirm.showAndWait().filter(button -> button == download).isEmpty()) {
            return;
        }
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
        AccountProvider provider = ProviderRegistry.find(type).orElse(null);
        if (provider == null) {
            status.setText("The provider implementation is not available in this build.");
            return;
        }
        try {
            AccountSetup setup = buildSetup(type, provider);
            Account account = Account.builder(Ids.uuid(), type.id())
                    .email(setup.email())
                    .displayName(setup.displayName())
                    .settings(setup.settings())
                    .credentialRef(Ids.shortId())
                    .build();
            context.store().saveAccount(account);
            AccountSessionFactory sessions = context.sessions();
            sessions.saveCredential(account, setup.credential());
            status.setText("The account was saved.");
            closeVerificationBrowser();
            stage.close();
        } catch (ProviderException e) {
            status.setText(e.userMessage());
        } catch (RuntimeException e) {
            LOG.warn("The account could not be saved: {}", e.getMessage());
            status.setText("The account could not be saved: " + e.getMessage());
        }
    }

    private AccountSetup buildSetup(ProviderType type, AccountProvider provider) {
        String email = emailField.getText() == null ? "" : emailField.getText().trim();
        String displayName = displayNameField.getText() == null ? "" : displayNameField.getText().trim();
        if (email.isBlank()) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "The email address is missing.", false);
        }
        if (type == ProviderType.GMAIL || type == ProviderType.MICROSOFT
                || (type == ProviderType.YAHOO && !clientIdField.getText().isBlank())) {
            if (pendingAuthorization == null || pendingSetup == null) {
                throw new ProviderException(ErrorCode.CONSENT_REQUIRED,
                        "Start the sign in first by pressing 'Open sign in page'.", false);
            }
            String redirect = redirectResultField.getText() == null ? ""
                    : redirectResultField.getText().trim();
            String code = extractCode(redirect);
            if (code.isBlank()) {
                throw new ProviderException(ErrorCode.CONSENT_REQUIRED,
                        "Paste the full redirect URL that the browser was sent to.", false);
            }
            OAuthCapableProvider oauth = (OAuthCapableProvider) provider;
            return oauth.completeAuthorization(pendingSetup, code, pendingAuthorization.state(),
                    extractState(redirect), pendingAuthorization.codeVerifier());
        }
        if (type == ProviderType.PROTON) {
            return buildProtonSetup(email, displayName);
        }
        if (type == ProviderType.GENERIC) {
            Map<String, String> settings = new LinkedHashMap<>();
            settings.put(ImapSettings.IMAP_HOST, imapHostField.getText());
            settings.put(ImapSettings.IMAP_PORT, imapPortField.getText());
            settings.put(ImapSettings.IMAP_SECURITY, imapSecurityField.getValue());
            settings.put(ImapSettings.SMTP_HOST, smtpHostField.getText());
            settings.put(ImapSettings.SMTP_PORT, smtpPortField.getText());
            settings.put(ImapSettings.SMTP_SECURITY, smtpSecurityField.getValue());
            settings.put(ImapSettings.USERNAME, email);
            return AccountSetup.builder(AuthMethod.PASSWORD)
                    .email(email)
                    .displayName(displayName)
                    .password(email, passwordField.getText() == null ? "" : passwordField.getText())
                    .settings(settings)
                    .build();
        }
        AuthMethod method = provider.capabilities().supportsAuthMethod(AuthMethod.APP_PASSWORD)
                ? AuthMethod.APP_PASSWORD : AuthMethod.PASSWORD;
        return AccountSetup.builder(method)
                .email(email)
                .displayName(displayName)
                .password(email, passwordField.getText() == null ? "" : passwordField.getText())
                .build();
    }

    /**
     * Builds a Proton setup from the Proton username and password. Signs in
     * against the Proton API right away (asking for a two-factor code when the
     * account needs one) and verifies that the mailbox keys unlock, so a wrong
     * password is reported before the account is saved.
     */
    private AccountSetup buildProtonSetup(String email, String displayName) {
        String loginPassword = passwordField.getText() == null ? "" : passwordField.getText();
        if (loginPassword.isEmpty()) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "Enter the Proton password.", false);
        }
        String mailboxPassword = mailboxPasswordField.getText() == null
                || mailboxPasswordField.getText().isEmpty()
                ? loginPassword : mailboxPasswordField.getText();
        String twoFactorCode = pendingTwoFactor && twoFactorField.getText() != null
                ? twoFactorField.getText().trim() : null;
        status.setText("Signing in to Proton…");
        ProtonAuth auth = new ProtonAuth(new DefaultProtonHttpTransport());
        ProtonAuth.Tokens tokens;
        try {
            tokens = auth.login(email, loginPassword, twoFactorCode);
            pendingTwoFactor = false;
            pendingCaptcha = false;
        } catch (ProtonAuth.TwoFactorRequired e) {
            pendingTwoFactor = true;
            updateVisibility(ProviderType.PROTON);
            throw new ProviderException(ErrorCode.CONSENT_REQUIRED,
                    "Enter the two-factor code from the authenticator app and press Save again.",
                    false, e);
        } catch (ProtonApiException.CaptchaRequired e) {
            pendingCaptcha = true;
            updateVisibility(ProviderType.PROTON);
            throw e;
        } catch (ProviderException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warn("Proton sign in failed: {}", e.getMessage());
            throw new ProviderException(ErrorCode.CONNECTION_REFUSED,
                    "Proton could not be reached. Check the connection and retry.", true, e);
        }
        java.util.Map<String, String> settings = new java.util.LinkedHashMap<>();
        settings.put(ProtonTransportMode.SETTING, ProtonTransportMode.API.settingValue());
        settings.put(ProtonProvider.SETTING_UID, tokens.uid());
        TokenSet tokenSet = new TokenSet(tokens.accessToken(),
                tokens.refreshToken(), tokens.expiresAt(), tokens.scope(), "", java.util.Map.of());
        Credential credential =
                new Credential(email, loginPassword, tokenSet, mailboxPassword);
        // Verify the mailbox keys unlock before saving anything.
        try (com.graalmail.provider.api.ProviderSession verify =
                     new ProtonApiSession(email,
                             new ProtonApiClient(new DefaultProtonHttpTransport(), auth, tokens),
                             mailboxPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8), email)) {
            ((ProtonApiSession) verify).unlock();
        } catch (ProviderException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warn("Proton mailbox verification failed: {}", e.getMessage());
            throw new ProviderException(ErrorCode.AUTHENTICATION_FAILED,
                    "The Proton mailbox could not be opened. "
                            + "With two-password mode, enter the mailbox password too.", false, e);
        }
        status.setText("Signed in to Proton; the account can be saved.");
        return AccountSetup.builder(AuthMethod.PASSWORD)
                .email(email)
                .displayName(displayName)
                .credential(credential)
                .settings(settings)
                .build();
    }

    private static String extractCode(String redirect) {
        int index = redirect.indexOf("code=");
        if (index < 0) {
            return "";
        }
        String value = redirect.substring(index + 5);
        int end = value.indexOf('&');
        return end < 0 ? value : value.substring(0, end);
    }

    private static String extractState(String redirect) {
        int index = redirect.indexOf("state=");
        if (index < 0) {
            return "";
        }
        String value = redirect.substring(index + 6);
        int end = value.indexOf('&');
        return end < 0 ? value : value.substring(0, end);
    }

    /**
     * The credential that was entered for a manual setup.
     *
     * @return the credential, never {@code null}
     */
    public Credential enteredCredential() {
        return Credential.password(emailField.getText() == null ? "" : emailField.getText(),
                passwordField.getText() == null ? "" : passwordField.getText());
    }

    /**
     * The security mode selected for the IMAP connection.
     *
     * @return the mode, {@link SecurityMode#SSL_TLS} by default
     */
    public SecurityMode imapSecurityMode() {
        return SecurityMode.parse(imapSecurityField.getValue());
    }
}
