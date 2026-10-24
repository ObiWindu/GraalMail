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
    private final Map<Node, Label> rowLabels = new java.util.HashMap<>();
    private final VBox stepContent = new VBox(12);
    private int currentStep;

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

        GridPane identity = new GridPane();
        GridPane connection = new GridPane();
        GridPane authorization = new GridPane();
        for (GridPane grid : new GridPane[]{identity, connection, authorization}) {
            grid.setHgap(10);
            grid.setVgap(8);
            grid.setPadding(new Insets(12));
        }
        addRow(identity, 0, "Provider", providerChoice);
        addRow(identity, 1, "Email", emailField);
        addRow(identity, 2, "Display name", displayNameField);
        int row = 0;
        row = addRow(connection, row, "IMAP server", imapHostField);
        row = addRow(connection, row, "IMAP port", imapPortField);
        row = addRow(connection, row, "IMAP security", imapSecurityField);
        row = addRow(connection, row, "SMTP server", smtpHostField);
        row = addRow(connection, row, "SMTP port", smtpPortField);
        row = addRow(connection, row, "SMTP security", smtpSecurityField);
        row = addRow(connection, row, "Password or app password", passwordField);
        row = addRow(connection, row, "OAuth client id", clientIdField);
        row = addRow(connection, row, "OAuth redirect URI", redirectUriField);
        addRow(authorization, 0, "Redirect result URL", redirectResultField);

        Button openBrowser = new Button("Open sign in page");
        openBrowser.setOnAction(event -> openAuthorizationPage());
        Button cancel = new Button("Cancel");
        cancel.setOnAction(event -> stage.close());
        Button continueButton = new Button("Continue");
        continueButton.setDefaultButton(true);
        Button back = new Button("Back");
        back.setOnAction(event -> { currentStep = Math.max(0, currentStep - 1); updateStep(identity, connection, authorization, openBrowser, back, continueButton); });
        continueButton.setOnAction(event -> {
            if (currentStep == 0) {
                if (emailField.getText() == null || emailField.getText().isBlank()) {
                    status.setText("Enter the email address for this account first.");
                    emailField.requestFocus();
                    return;
                }
                currentStep = 1;
                updateStep(identity, connection, authorization, openBrowser, back, continueButton);
            } else if (requiresOAuth()) {
                currentStep = 2;
                updateStep(identity, connection, authorization, openBrowser, back, continueButton);
            } else save();
        });
        saveButton.setOnAction(event -> save());

        HBox buttons = new HBox(8, back, continueButton, openBrowser, saveButton, cancel);
        buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);

        Label heading = new Label("Add an account");
        heading.getStyleClass().add("compose-heading");
        Label help = new Label("Choose your provider and follow the sign-in steps. Your mail stays in GraalMail's local cache.");
        help.setWrapText(true);
        VBox content = new VBox(12, heading, help, stepContent, status, buttons);
        content.getStyleClass().add("account-setup");
        if (new DesktopSettings(context.store()).theme().equals("Dark")) content.getStyleClass().add("dark-theme");
        content.setPadding(new Insets(16));
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        javafx.scene.Scene scene = new javafx.scene.Scene(scroll, 600, 680);
        var stylesheet = getClass().getResource("/styles/mail.css");
        if (stylesheet != null) scene.getStylesheets().add(stylesheet.toExternalForm());
        stage.setScene(scene);
        updateVisibility(ProviderType.GENERIC);
        currentStep = 0;
        updateStep(identity, connection, authorization, openBrowser, back, continueButton);
        stage.showAndWait();
    }

    private void updateStep(GridPane identity, GridPane connection, GridPane authorization,
                            Button openBrowser, Button back, Button continueButton) {
        stepContent.getChildren().clear();
        if (currentStep == 0) {
            stepContent.getChildren().addAll(new Label("1. Choose provider and account"), identity);
        } else if (currentStep == 1) {
            stepContent.getChildren().addAll(new Label("2. Configure sign-in"), connection);
        } else {
            stepContent.getChildren().addAll(new Label("3. Complete browser sign-in"),
                    new Label("Open the provider page, finish signing in, then paste the full redirect URL below."),
                    authorization);
        }
        back.setVisible(currentStep > 0);
        back.setManaged(currentStep > 0);
        boolean oauthStep = currentStep == 2 && requiresOAuth();
        openBrowser.setVisible(oauthStep);
        openBrowser.setManaged(oauthStep);
        saveButton.setVisible(oauthStep);
        saveButton.setManaged(oauthStep);
        saveButton.setDefaultButton(oauthStep);
        continueButton.setVisible(currentStep < 2);
        continueButton.setManaged(currentStep < 2);
        continueButton.setDefaultButton(currentStep < 2);
        continueButton.setText(currentStep == 1 && !requiresOAuth() ? "Save account" : "Continue");
        setRowVisible(redirectResultField, oauthStep);
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
        setRowVisible(imapHostField, generic);
        setRowVisible(imapPortField, generic);
        setRowVisible(imapSecurityField, generic);
        setRowVisible(smtpHostField, generic);
        setRowVisible(smtpPortField, generic);
        setRowVisible(smtpSecurityField, generic);
        setRowVisible(passwordField, type != ProviderType.GMAIL && type != ProviderType.MICROSOFT);
        setRowVisible(clientIdField, type != ProviderType.GENERIC);
        setRowVisible(redirectUriField, type != ProviderType.GENERIC);
        setRowVisible(redirectResultField, type != ProviderType.GENERIC);
    }

    private void setRowVisible(Node field, boolean visible) {
        field.setVisible(visible);
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
