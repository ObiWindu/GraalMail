/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.ui;

import android.os.Bundle;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.graalmail.android.AppContext;
import com.graalmail.android.R;
import com.graalmail.core.domain.Account;
import com.graalmail.core.error.ProviderException;
import com.graalmail.core.util.Log;
import com.graalmail.provider.api.AccountProvider;
import com.graalmail.provider.api.ProviderCapabilities;
import com.graalmail.provider.api.ProviderRegistry;
import com.graalmail.provider.api.ProviderSession;
import com.graalmail.provider.api.ProviderType;

import org.slf4j.Logger;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The diagnostics screen of the Android client.
 *
 * <p>The screen shows the providers that are available on the device, the
 * capabilities of every account and the result of a connection test.</p>
 */
public final class DiagnosticsActivity extends AppCompatActivity {

    private static final Logger LOG = Log.of(DiagnosticsActivity.class);

    private AppContext context;
    private TextView report;
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diagnostics);
        EdgeToEdge.apply(this);
        context = AppContext.of(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.diagnostics_title);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(view -> finish());

        report = findViewById(R.id.diagnostics_report);
        findViewById(R.id.diagnostics_test).setOnClickListener(view -> collect());
        findViewById(R.id.diagnostics_copy).setOnClickListener(view -> copyRedactedReport());
    }

    private void copyRedactedReport() {
        String redacted = EMAIL.matcher(report.getText()).replaceAll("[redacted email]");
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.diagnostics_title), redacted));
            android.widget.Toast.makeText(this, R.string.diagnostics_report_copied,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        collect();
    }

    private void collect() {
        context.io().execute(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append(getString(R.string.diagnostics_version, "0.1.0")).append('\n');
            sb.append("android: ").append(android.os.Build.VERSION.RELEASE)
                    .append(" (api ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
            sb.append("device: ").append(android.os.Build.MANUFACTURER)
                    .append(' ').append(android.os.Build.MODEL).append("\n\n");

            List<AccountProvider> providers = ProviderRegistry.loadAll();
            sb.append(getString(R.string.diagnostics_capabilities)).append('\n');
            for (AccountProvider provider : providers) {
                ProviderCapabilities capabilities = provider.capabilities();
                sb.append("- ").append(provider.displayName()).append('\n');
                sb.append("    authentication: ").append(capabilities.authMethods()).append('\n');
                sb.append("    sending: ")
                        .append(capabilities.sendCapable()
                                ? getString(R.string.diagnostics_yes)
                                : getString(R.string.diagnostics_no)).append('\n');
                sb.append("    attachments: ")
                        .append(capabilities.attachments()
                                ? getString(R.string.diagnostics_yes)
                                : getString(R.string.diagnostics_no)).append('\n');
                for (String restriction : capabilities.restrictions()) {
                    sb.append("    ").append(getString(R.string.diagnostics_restrictions))
                            .append(": ").append(restriction).append('\n');
                }
            }
            sb.append('\n');

            List<Account> accounts = context.store().listAccounts();
            if (accounts.isEmpty()) {
                sb.append(getString(R.string.diagnostics_no_accounts)).append('\n');
            }
            for (Account account : accounts) {
                sb.append(getString(R.string.diagnostics_account, account.email())).append('\n');
                sb.append(getString(R.string.diagnostics_provider, account.providerId())).append('\n');
                sb.append("    ").append(getString(R.string.diagnostics_connection))
                        .append(": ").append(test(account)).append('\n');
                String last = context.store().setting("sync.lastSuccess." + account.id(), "");
                sb.append("    ").append(getString(R.string.diagnostics_last_sync,
                        last.isEmpty() ? getString(R.string.diagnostics_never_synced) : last))
                        .append('\n');
                sb.append("    ").append(getString(R.string.diagnostics_pending,
                        context.store().pendingActions(account.id()).size())).append('\n');
            }

            String text = sb.toString();
            runOnUiThread(() -> report.setText(text));
        });
    }

    private String test(Account account) {
        ProviderType type = ProviderType.fromId(account.providerId());
        if (ProviderRegistry.find(type).isEmpty()) {
            return getString(R.string.diagnostics_provider_missing);
        }
        try (ProviderSession session = context.sessions().open(account)) {
            int mailboxes = session.mailboxes().listFolders().size();
            return getString(R.string.diagnostics_connection_ok) + " (" + mailboxes + ")";
        } catch (ProviderException e) {
            return getString(R.string.diagnostics_connection_failed) + ": " + e.userMessage();
        } catch (RuntimeException e) {
            LOG.warn("The connection test failed: {}", e.getMessage());
            return getString(R.string.diagnostics_connection_failed) + ": " + e.getMessage();
        }
    }
}
