/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
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
    private EditText emailField;
    private EditText passwordField;
    private View passwordContainer;
    private EditText displayNameField;
    private EditText imapHostField;
    private EditText imapPortField;
    private EditText smtpHostField;
    private EditText smtpPortField;
    private EditText usernameField;
    private Spinner imapSecurity;
    private Spinner smtpSecurity;
    private View genericSection;
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
        status = findViewById(R.id.setup_status);
        authExplanation = findViewById(R.id.setup_auth_explanation);
        connectButton = findViewById(R.id.setup_connect);

        List<String> names = new ArrayList<>();
        for (ProviderType type : ProviderType.values()) {
            if (type == ProviderType.PROTON) {
                continue;
            }
            providers.add(type);
            names.add(type.displayName());
        }
        providerSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, names));
        providerSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position,
                                       long id) {
                genericSection.setVisibility(providers.get(position) == ProviderType.GENERIC
                        ? View.VISIBLE : View.GONE);
                updateProviderAuth(providers.get(position));
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        connectButton.setOnClickListener(view -> connect());
    }

    private void configureSecuritySpinner(Spinner spinner) {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.setup_secure_security_modes, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private void updateProviderAuth(ProviderType type) {
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

    private void store(ProviderType type, String email, String password, Map<String, String> settings) {
        String accountId = Ids.uuid();
        Account account = Account.builder(accountId, type.id())
                .email(email)
                .displayName(displayNameField.getText().toString().trim())
                .settings(settings)
                .credentialRef(accountId)
                .build();
        context.store().saveAccount(account);
        Credential credential = Credential.password(email, password);
        context.sessions().saveCredential(account, credential);
    }
}
