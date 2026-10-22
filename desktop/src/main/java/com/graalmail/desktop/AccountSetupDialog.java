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
import com.graalmail.provider.imap.ImapSettings;
import com.graalmail.provider.imap.SecurityMode;
import com.graalmail.security.model.Credential;
import com.graalmail.security.oauth.AuthorizationRequest;
import com.graalmail.sync.AccountSessionFactory;
import java.awt.Desktop;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
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
    private final TextField clientIdField = new TextField();
    private final TextField redirectUriField = new TextField("http://localhost:8080/oauth/callback");
    private final TextField redirectResultField = new TextField();
    private final Label status = new Label("");
    private final Button saveButton = new Button("Save account");

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
     */
    public void showAndWait() {
        providerChoice.getItems().setAll(ProviderType.values());
        providerChoice.setValue(ProviderType.GENERIC);
        providerChoice.valueProperty().addListener((observable, old, value) -> updateVisibility(value));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        int row = 0;
        row = addRow(grid, row, "Provider", providerChoice);
        row = addRow(grid, row, "Email", emailField);
        row = addRow(grid, row, "Display name", displayNameField);
        row = addRow(grid, row, "IMAP server", imapHostField);
        row = addRow(grid, row, "IMAP port", imapPortField);
        row = addRow(grid, row, "IMAP security", imapSecurityField);
        row = addRow(grid, row, "SMTP server", smtpHostField);
        row = addRow(grid, row, "SMTP port", smtpPortField);
        row = addRow(grid, row, "SMTP security", smtpSecurityField);
        row = addRow(grid, row, "Password or app password", passwordField);
        row = addRow(grid, row, "OAuth client id", clientIdField);
        row = addRow(grid, row, "OAuth redirect URI", redirectUriField);
        row = addRow(grid, row, "Redirect result", redirectResultField);

        Button openBrowser = new Button("Open sign in page");
        openBrowser.setOnAction(event -> openAuthorizationPage());
        Button cancel = new Button("Cancel");
        cancel.setOnAction(event -> stage.close());
        saveButton.setOnAction(event -> save());

        VBox buttons = new VBox(8, openBrowser, saveButton, cancel);
        buttons.setPadding(new Insets(12));

        VBox root = new VBox(10, grid, status, buttons);
        root.setPadding(new Insets(10));
        stage.setScene(new javafx.scene.Scene(root, 560, 640));
        updateVisibility(ProviderType.GENERIC);
        stage.showAndWait();
    }

    private int addRow(GridPane grid, int row, String label, javafx.scene.Node node) {
        grid.add(new Label(label), 0, row);
        grid.add(node, 1, row);
        return row + 1;
    }

    private void updateVisibility(ProviderType type) {
        boolean generic = type == ProviderType.GENERIC;
        imapHostField.setDisable(!generic);
        imapPortField.setDisable(!generic);
        imapSecurityField.setDisable(!generic);
        smtpHostField.setDisable(!generic);
        smtpPortField.setDisable(!generic);
        smtpSecurityField.setDisable(!generic);
        passwordField.setDisable(type == ProviderType.GMAIL || type == ProviderType.MICROSOFT);
        clientIdField.setDisable(type == ProviderType.GENERIC);
        redirectUriField.setDisable(type == ProviderType.GENERIC);
        redirectResultField.setDisable(type == ProviderType.GENERIC);
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
            Desktop.getDesktop().browse(URI.create(pendingAuthorization.authorizationUrl()));
            status.setText("A browser window was opened. Paste the full redirect URL into the "
                    + "redirect field and press Save.");
        } catch (Exception e) {
            LOG.warn("The authorization page could not be opened: {}", e.getMessage());
            status.setText("The sign in page could not be opened: " + e.getMessage());
        }
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
