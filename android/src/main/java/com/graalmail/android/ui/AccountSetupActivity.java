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
    private EditText displayNameField;
    private EditText imapHostField;
    private EditText imapPortField;
    private EditText smtpHostField;
    private EditText smtpPortField;
    private View genericSection;
    private TextView status;
    private final List<ProviderType> providers = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account_setup);
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
        displayNameField = findViewById(R.id.setup_display_name);
        imapHostField = findViewById(R.id.setup_imap_host);
        imapPortField = findViewById(R.id.setup_imap_port);
        smtpHostField = findViewById(R.id.setup_smtp_host);
        smtpPortField = findViewById(R.id.setup_smtp_port);
        genericSection = findViewById(R.id.setup_generic_section);
        status = findViewById(R.id.setup_status);

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
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        findViewById(R.id.setup_connect).setOnClickListener(view -> connect());
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

        String password = passwordField.getText().toString();
        AccountSetup setup = AccountSetup.builder(AuthMethod.PASSWORD)
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
                }
            });
        });
    }

    private Map<String, String> settings(ProviderType type) {
        Map<String, String> settings = new LinkedHashMap<>();
        if (type == ProviderType.GENERIC) {
            settings.put("imap.host", imapHostField.getText().toString().trim());
            settings.put("imap.port", imapPortField.getText().toString().trim());
            settings.put("imap.security", "SSL_TLS");
            settings.put("smtp.host", smtpHostField.getText().toString().trim());
            settings.put("smtp.port", smtpPortField.getText().toString().trim());
            settings.put("smtp.security", "SSL_TLS");
            settings.put("auth.username", emailField.getText().toString().trim());
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
