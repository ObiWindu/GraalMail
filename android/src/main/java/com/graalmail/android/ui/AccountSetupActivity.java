/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.graalmail.android.AppContext;
import com.graalmail.android.R;
import com.graalmail.core.domain.Account;
import com.graalmail.core.error.ProviderException;
import com.graalmail.core.util.Ids;
import com.graalmail.core.util.Log;
import com.graalmail.provider.api.AccountProvider;
import com.graalmail.provider.api.AccountSetup;
import com.graalmail.provider.api.AuthMethod;
import com.graalmail.provider.api.ProviderRegistry;
import com.graalmail.provider.api.ProviderSession;
import com.graalmail.provider.api.ProviderType;
import com.graalmail.provider.proton.ProtonAndroidIntegration;
import com.graalmail.security.model.Credential;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The account setup screen of the Android client.
 *
 * <p>The screen collects the address, the password and, for a generic
 * provider, the server settings. The connection is tested before the account
 * is stored, so that a wrong password is reported immediately.</p>
 */
public final class AccountSetupActivity extends AppCompatActivity {

    private static final Logger LOG = Log.of(AccountSetupActivity.class);

    private AppContext context;
    private Spinner providerSpinner;
    private TextView providerDetectedLabel;
    private boolean providerManuallyChosen;
    private boolean adjustingProviderChoice;
    private EditText emailField;
    private EditText passwordField;
    private View passwordContainer;
    private View displayNameContainer;
    private View passwordFieldContainer;
    private EditText displayNameField;
    private EditText imapHostField;
    private EditText imapPortField;
    private EditText smtpHostField;
    private EditText smtpPortField;
    private EditText usernameField;
    private Spinner imapSecurity;
    private Spinner smtpSecurity;
    private View genericSection;
    private View protonSection;
    private EditText protonMailboxField;
    private View protonTwoFactorContainer;
    private EditText protonTwoFactorField;
    private View protonOpenLoginButton;
    private boolean pendingProtonTwoFactor;
    private boolean pendingProtonCaptcha;
    private TextView status;
    private TextView authExplanation;
    private View connectButton;
    private boolean authUnavailable;
    private final List<ProviderType> providers = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account_setup);
        EdgeToEdge.apply(this);
        context = AppContext.of(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.setup_title);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(view -> finish());

        providerSpinner = findViewById(R.id.setup_provider);
        emailField = findViewById(R.id.setup_email);
        passwordField = findViewById(R.id.setup_password);
        passwordContainer = findViewById(R.id.setup_password_container);
        displayNameContainer = findViewById(R.id.setup_display_name_container);
        passwordFieldContainer = findViewById(R.id.setup_password_field_container);
        displayNameField = findViewById(R.id.setup_display_name);
        imapHostField = findViewById(R.id.setup_imap_host);
        imapPortField = findViewById(R.id.setup_imap_port);
        smtpHostField = findViewById(R.id.setup_smtp_host);
        smtpPortField = findViewById(R.id.setup_smtp_port);
        usernameField = findViewById(R.id.setup_username);
        imapSecurity = findViewById(R.id.setup_imap_security);
        smtpSecurity = findViewById(R.id.setup_smtp_security);
        configureSecuritySpinner(imapSecurity);
        configureSecuritySpinner(smtpSecurity);
        genericSection = findViewById(R.id.setup_generic_section);
        protonSection = findViewById(R.id.setup_proton_section);
        protonMailboxField = findViewById(R.id.setup_proton_mailbox);
        protonTwoFactorContainer = findViewById(R.id.setup_proton_two_factor_container);
        protonTwoFactorField = findViewById(R.id.setup_proton_two_factor);
        status = findViewById(R.id.setup_status);
        authExplanation = findViewById(R.id.setup_auth_explanation);
        connectButton = findViewById(R.id.setup_connect);
        Button protonOpenApp = findViewById(R.id.setup_proton_open_app);
        protonOpenApp.setOnClickListener(view -> openProtonApp());
        Button protonOpenWeb = findViewById(R.id.setup_proton_open_web);
        protonOpenWeb.setOnClickListener(view -> openProtonWeb());
        protonOpenLoginButton = findViewById(R.id.setup_proton_open_login);
        protonOpenLoginButton.setOnClickListener(view -> openProtonLogin());

        List<String> names = new ArrayList<>();
        for (ProviderType type : ProviderType.values()) {
            providers.add(type);
            names.add(type == ProviderType.PROTON
                    ? getString(R.string.setup_provider_proton) : type.displayName());
        }
        providerSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, names));
        providerDetectedLabel = findViewById(R.id.setup_provider_detected);
        findViewById(R.id.setup_provider_change).setOnClickListener(view -> {
            providerSpinner.setVisibility(View.VISIBLE);
            providerSpinner.requestFocus();
        });
        providerSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position,
                                       long id) {
                if (!adjustingProviderChoice) {
                    providerManuallyChosen = true;
                }
                refreshDetectedLabel();
                genericSection.setVisibility(providers.get(position) == ProviderType.GENERIC
                        ? View.VISIBLE : View.GONE);
                updateProviderAuth(providers.get(position));            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        emailField.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable text) {
                providerManuallyChosen = false;
                ProviderType deduced = com.graalmail.provider.api.ProviderDeduction
                        .deduceProvider(text.toString());
                int index = providers.indexOf(deduced);
                if (index >= 0
                        && index != providerSpinner.getSelectedItemPosition()) {
                    adjustingProviderChoice = true;
                    try {
                        providerSpinner.setSelection(index);
                    } finally {
                        adjustingProviderChoice = false;
                    }
                }
                refreshDetectedLabel();
            }
        });
        providerManuallyChosen = false;
        refreshDetectedLabel();

        connectButton.setOnClickListener(view -> connect());
    }

    private void refreshDetectedLabel() {
        int position = providerSpinner.getSelectedItemPosition();
        ProviderType type = position >= 0 && position < providers.size()
                ? providers.get(position) : ProviderType.GENERIC;
        String name = type == ProviderType.PROTON
                ? getString(R.string.setup_provider_proton) : type.displayName();
        providerDetectedLabel.setText(getString(providerManuallyChosen
                ? R.string.setup_provider_manual : R.string.setup_provider_detected, name));
    }

    private void configureSecuritySpinner(Spinner spinner) {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.setup_secure_security_modes, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private void updateProviderAuth(ProviderType type) {
        if (type == ProviderType.PROTON) {
            // Direct Proton API login with the Proton username and password.
            // The mailbox password field stays optional for two-password mode,
            // and the official app links remain as alternatives below it.
            authUnavailable = false;
            protonSection.setVisibility(View.VISIBLE);
            protonTwoFactorContainer.setVisibility(
                    pendingProtonTwoFactor ? View.VISIBLE : View.GONE);
            protonOpenLoginButton.setVisibility(
                    pendingProtonCaptcha ? View.VISIBLE : View.GONE);
            passwordContainer.setVisibility(View.VISIBLE);
            displayNameContainer.setVisibility(View.VISIBLE);
            passwordFieldContainer.setVisibility(View.VISIBLE);
            genericSection.setVisibility(View.GONE);
            passwordField.setHint(R.string.setup_password);
            authExplanation.setText(R.string.setup_proton_auth_hint);
            connectButton.setEnabled(true);
            status.setText("");
            return;
        }
        pendingProtonTwoFactor = false;
        pendingProtonCaptcha = false;
        protonSection.setVisibility(View.GONE);
        passwordContainer.setVisibility(View.VISIBLE);
        displayNameContainer.setVisibility(View.VISIBLE);
        passwordFieldContainer.setVisibility(View.VISIBLE);
        AccountProvider provider = ProviderRegistry.find(type).orElse(null);
        boolean passwordAuth = provider != null && (provider.capabilities().supportsAuthMethod(AuthMethod.PASSWORD)
                || provider.capabilities().supportsAuthMethod(AuthMethod.APP_PASSWORD));
        boolean oauthOnly = provider != null && provider.capabilities().supportsAuthMethod(AuthMethod.OAUTH2)
                && !passwordAuth;
        authUnavailable = oauthOnly;
        passwordContainer.setVisibility(oauthOnly ? View.GONE : View.VISIBLE);
        connectButton.setEnabled(!oauthOnly);
        if (oauthOnly) {
            authExplanation.setText(R.string.setup_oauth_android_unavailable);
        } else if (type == ProviderType.YAHOO) {
            passwordField.setHint(R.string.setup_app_password);
            authExplanation.setText(R.string.setup_yahoo_auth_hint);
        } else {
            passwordField.setHint(R.string.setup_password);
            authExplanation.setText(type == ProviderType.GENERIC
                    ? R.string.setup_generic_auth_hint : R.string.setup_app_password_hint);
        }
    }

    private void connect() {
        ProviderType type = providers.get(providerSpinner.getSelectedItemPosition());
        if (type == ProviderType.PROTON) {
            connectProton();
            return;
        }
        String email = emailField.getText().toString().trim();
        if (email.isEmpty()) {
            status.setText(R.string.setup_field_required);
            return;
        }
        AccountProvider provider = ProviderRegistry.find(type).orElse(null);
        if (provider == null) {
            status.setText(getString(R.string.setup_no_provider_implementation, type.displayName()));
            return;
        }
        status.setText(R.string.setup_connecting);
        connectButton.setEnabled(false);

        String password = passwordField.getText().toString();
        AuthMethod authMethod = type == ProviderType.YAHOO ? AuthMethod.APP_PASSWORD : AuthMethod.PASSWORD;
        AccountSetup setup = AccountSetup.builder(authMethod)
                .email(email)
                .displayName(displayNameField.getText().toString().trim())
                .password(email, password)
                .settings(settings(type))
                .build();

        context.io().execute(() -> {
            String message;
            boolean stored = false;
            try (ProviderSession session = provider.openSession(setup)) {
                message = getString(R.string.setup_connection_ok,
                        session.mailboxes().listFolders().size() + " mailbox(es)");
                stored = true;
            } catch (ProviderException e) {
                message = getString(R.string.setup_connection_failed, e.userMessage());
            } catch (RuntimeException e) {
                LOG.warn("The connection could not be established: {}", e.getMessage());
                message = getString(R.string.setup_connection_failed, e.getMessage());
            }
            if (stored) {
                store(type, email, password, setup.settings());
            }
            String text = message;
            boolean done = stored;
            runOnUiThread(() -> {
                status.setText(text);
                if (done) {
                    Toast.makeText(this, R.string.setup_account_added, Toast.LENGTH_LONG).show();
                    finish();
                } else {
                    connectButton.setEnabled(!authUnavailable);
                }
            });
        });
    }

    private void connectProton() {
        String email = emailField.getText().toString().trim();
        if (email.isEmpty()) {
            status.setText(R.string.setup_field_required);
            return;
        }
        String loginPassword = passwordField.getText().toString();
        if (loginPassword.isEmpty()) {
            status.setText(R.string.setup_field_required);
            return;
        }
        String mailboxPassword = protonMailboxField.getText().toString();
        if (mailboxPassword.isEmpty()) {
            mailboxPassword = loginPassword;
        }
        String twoFactorCode = pendingProtonTwoFactor
                ? protonTwoFactorField.getText().toString().trim() : null;
        AccountProvider provider = ProviderRegistry.find(ProviderType.PROTON).orElse(null);
        if (provider == null) {
            status.setText(getString(R.string.setup_no_provider_implementation, "Proton Mail"));
            return;
        }
        status.setText(R.string.setup_connecting);
        connectButton.setEnabled(false);
        String mailboxSecret = mailboxPassword;
        context.io().execute(() -> {
            String message;
            boolean stored = false;
            AccountSetup setup = null;
            try {
                com.graalmail.provider.proton.api.ProtonAuth auth =
                        new com.graalmail.provider.proton.api.ProtonAuth(
                                new com.graalmail.provider.proton.api.DefaultProtonHttpTransport());
                com.graalmail.provider.proton.api.ProtonAuth.Tokens tokens =
                        auth.login(email, loginPassword, twoFactorCode);
                pendingProtonTwoFactor = false;
                pendingProtonCaptcha = false;
                java.util.Map<String, String> protonSettings = new LinkedHashMap<>();
                protonSettings.put(com.graalmail.provider.proton.ProtonTransportMode.SETTING,
                        com.graalmail.provider.proton.ProtonTransportMode.API.settingValue());
                protonSettings.put(com.graalmail.provider.proton.ProtonProvider.SETTING_UID,
                        tokens.uid());
                com.graalmail.security.model.TokenSet tokenSet =
                        new com.graalmail.security.model.TokenSet(tokens.accessToken(),
                                tokens.refreshToken(), tokens.expiresAt(), tokens.scope(), "",
                                java.util.Map.of());
                com.graalmail.security.model.Credential credential =
                        new com.graalmail.security.model.Credential(email, loginPassword,
                                tokenSet, mailboxSecret);
                setup = AccountSetup.builder(AuthMethod.PASSWORD)
                        .email(email)
                        .displayName(displayNameField.getText().toString().trim())
                        .credential(credential)
                        .settings(protonSettings)
                        .build();
                try (ProviderSession session = provider.openSession(setup)) {
                    message = getString(R.string.setup_connection_ok,
                            session.mailboxes().listFolders().size() + " mailbox(es)");
                    stored = true;
                }
            } catch (com.graalmail.provider.proton.api.ProtonAuth.TwoFactorRequired e) {
                pendingProtonTwoFactor = true;
                message = getString(R.string.setup_proton_two_factor_asked);
            } catch (com.graalmail.provider.proton.api.ProtonApiException.CaptchaRequired e) {
                pendingProtonCaptcha = true;
                message = e.userMessage();
            } catch (ProviderException e) {
                message = getString(R.string.setup_connection_failed, e.userMessage());
            } catch (RuntimeException e) {
                LOG.warn("The Proton connection could not be established: {}", e.getMessage());
                message = getString(R.string.setup_connection_failed, e.getMessage());
            }
            AccountSetup done = stored ? setup : null;
            String text = message;
            boolean showCaptchaButton = pendingProtonCaptcha;
            runOnUiThread(() -> {
                status.setText(text);
                if (done != null) {
                    protonTwoFactorContainer.setVisibility(View.GONE);
                    protonOpenLoginButton.setVisibility(View.GONE);
                    store(ProviderType.PROTON, email, done.credential(), done.settings());
                    Toast.makeText(this, R.string.setup_account_added, Toast.LENGTH_LONG).show();
                    finish();
                } else {
                    protonTwoFactorContainer.setVisibility(
                            pendingProtonTwoFactor ? View.VISIBLE : View.GONE);
                    protonOpenLoginButton.setVisibility(showCaptchaButton ? View.VISIBLE : View.GONE);
                    connectButton.setEnabled(true);
                }
            });
        });
    }

    private Map<String, String> settings(ProviderType type) {
        Map<String, String> settings = new LinkedHashMap<>();
        if (type == ProviderType.GENERIC) {
            settings.put("imap.host", imapHostField.getText().toString().trim());
            settings.put("imap.port", imapPortField.getText().toString().trim());
            settings.put("imap.security", imapSecurity.getSelectedItemPosition() == 1 ? "STARTTLS" : "SSL_TLS");
            settings.put("smtp.host", smtpHostField.getText().toString().trim());
            settings.put("smtp.port", smtpPortField.getText().toString().trim());
            settings.put("smtp.security", smtpSecurity.getSelectedItemPosition() == 1 ? "STARTTLS" : "SSL_TLS");
            String username = usernameField.getText().toString().trim();
            settings.put("auth.username", username.isEmpty()
                    ? emailField.getText().toString().trim() : username);
        }
        return settings;
    }

    private void openProtonApp() {
        PackageManager packages = getPackageManager();
        Intent launch = packages.getLaunchIntentForPackage(ProtonAndroidIntegration.APP_PACKAGE);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(launch);
                return;
            } catch (ActivityNotFoundException e) {
                LOG.warn("The Proton Mail app could not be opened: {}", e.getMessage());
            }
        }
        Toast.makeText(this, R.string.proton_app_missing, Toast.LENGTH_LONG).show();
        openProtonWeb();
    }

    private void openProtonWeb() {
        Intent view = new Intent(Intent.ACTION_VIEW,
                Uri.parse(ProtonAndroidIntegration.webUrl()));
        try {
            startActivity(view);
        } catch (ActivityNotFoundException e) {
            LOG.warn("No browser is available for the Proton web client: {}", e.getMessage());
            status.setText(R.string.proton_no_browser);
        }
    }

    private void openProtonLogin() {
        Intent view = new Intent(Intent.ACTION_VIEW,
                Uri.parse(com.graalmail.provider.proton.api.ProtonApiException.HUMAN_VERIFICATION_LOGIN_URL));
        try {
            startActivity(view);
        } catch (ActivityNotFoundException e) {
            LOG.warn("No browser is available for the Proton login: {}", e.getMessage());
            status.setText(R.string.proton_no_browser);
        }
    }

    private void store(ProviderType type, String email, String password, Map<String, String> settings) {
        store(type, email, Credential.password(email, password), settings);
    }

    private void store(ProviderType type, String email, Credential credential,
                       Map<String, String> settings) {
        String accountId = Ids.uuid();
        Account account = Account.builder(accountId, type.id())
                .email(email)
                .displayName(displayNameField.getText().toString().trim())
                .settings(settings)
                .credentialRef(accountId)
                .build();
        context.store().saveAccount(account);
        context.sessions().saveCredential(account, credential);
    }
}
