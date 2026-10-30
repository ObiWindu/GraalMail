/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.graalmail.android.AppContext;
import com.graalmail.android.R;
import com.graalmail.android.notify.NotificationHelper;
import com.graalmail.core.domain.Account;
import com.graalmail.core.domain.Folder;
import com.graalmail.core.domain.FolderType;
import com.graalmail.core.util.Log;
import com.graalmail.sync.SyncReport;

import org.slf4j.Logger;

import java.util.List;

/**
 * The start screen of the Android client.
 *
 * <p>It shows the configured accounts with their mailboxes and starts the
 * synchronisation when the user pulls the list down.</p>
 */
public final class MailboxListActivity extends AppCompatActivity implements FolderAdapter.OnFolderSelected {

    private static final Logger LOG = Log.of(MailboxListActivity.class);

    private AppContext context;
    private FolderAdapter adapter;
    private SwipeRefreshLayout refresh;
    private TextView emptyView;
    private boolean syncInProgress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mailbox_list);
        EdgeToEdge.apply(this);
        context = AppContext.of(this);
        NotificationHelper.createChannel(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        RecyclerView list = findViewById(R.id.mailbox_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FolderAdapter(this, this);
        list.setAdapter(adapter);

        emptyView = findViewById(R.id.mailbox_empty);
        refresh = findViewById(R.id.mailbox_refresh);
        refresh.setOnRefreshListener(this::synchronise);

        findViewById(R.id.mailbox_add).setOnClickListener(view ->
                startActivity(new Intent(this, AccountSetupActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_mailbox_list, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_sync) {
            synchronise();
            return true;
        }
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (id == R.id.action_diagnostics) {
            startActivity(new Intent(this, DiagnosticsActivity.class));
            return true;
        }
        if (id == R.id.action_add_account) {
            startActivity(new Intent(this, AccountSetupActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void reload() {
        List<Account> accounts = context.store().listAccounts();
        
        // Add unified inbox entry if there are multiple accounts with inboxes
        if (accounts.size() > 1) {
            // We'll handle this in the adapter
        }
        
        adapter.submit(accounts, (account, accountId) -> context.store().listFolders(accountId));
        boolean empty = accounts.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        findViewById(R.id.mailbox_add).setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void synchronise() {
        if (syncInProgress) return;
        syncInProgress = true;
        refresh.setRefreshing(true);
        context.io().execute(() -> {
            int newMessages = 0;
            String failure = null;
            String twoFactorAccountId = null;
            try {
                List<SyncReport> reports = context.syncEngine().syncAll();
                for (SyncReport report : reports) {
                    newMessages += report.newMessages();
                    if (!report.successful() && failure == null) {
                        failure = report.errors().isEmpty() ? "" : report.errors().get(0);
                    }
                    if (twoFactorAccountId == null && report.errors().stream()
                            .anyMatch(error -> error.toLowerCase(java.util.Locale.ROOT)
                                    .contains("two-factor code"))) {
                        twoFactorAccountId = report.accountId();
                    }
                }
            } catch (RuntimeException e) {
                LOG.warn("The synchronisation failed: {}", e.getMessage());
                failure = e.getMessage();
            }
            int count = newMessages;
            String message = failure;
            String tfaAccount = twoFactorAccountId;
            runOnUiThread(() -> {
                syncInProgress = false;
                refresh.setRefreshing(false);
                reload();
                if (count > 0) {
                    NotificationHelper.notifyNewMail(this, count);
                }
                if (message != null && !message.isEmpty()) {
                    com.google.android.material.snackbar.Snackbar.make(findViewById(R.id.mailbox_refresh),
                            getString(R.string.mailbox_sync_failed, message),
                            com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE)
                            .setAction(R.string.action_retry, view -> synchronise()).show();
                }
                if (tfaAccount != null) {
                    promptTwoFactorCode(tfaAccount);
                }
            });
        });
    }

    /** Asks for a fresh TOTP code and refreshes the stored Proton session. */
    private void promptTwoFactorCode(String accountId) {
        Account account = context.store().account(accountId).orElse(null);
        if (account == null) {
            return;
        }
        android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint(getString(R.string.setup_proton_two_factor));
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.setup_proton_two_factor))
                .setMessage(account.email())
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String code = input.getText().toString().trim();
                    if (code.isEmpty()) {
                        return;
                    }
                    context.io().execute(() -> {
                        try {
                            var fresh = context.sessions()
                                    .reauthenticateWithTwoFactor(account, code);
                            Account updated = Account.builder(account.id(), account.providerId())
                                    .email(account.email())
                                    .displayName(account.displayName())
                                    .settings(fresh.settings())
                                    .credentialRef(account.credentialRef())
                                    .createdAt(account.createdAt())
                                    .enabled(account.enabled())
                                    .build();
                            context.store().saveAccount(updated);
                            runOnUiThread(this::synchronise);
                        } catch (RuntimeException e) {
                            LOG.warn("Two-factor re-authentication failed: {}", e.getMessage());
                            runOnUiThread(this::synchronise);
                        }
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onFolderSelected(Account account, Folder folder) {
        if (folder.type() == FolderType.UNIFIED_INBOX) {
            // Open unified inbox - show messages from all account inboxes
            Intent intent = new Intent(this, UnifiedInboxActivity.class);
            startActivity(intent);
        } else {
            Intent intent = new Intent(this, MessageListActivity.class);
            intent.putExtra(MessageListActivity.EXTRA_ACCOUNT_ID, account.id());
            intent.putExtra(MessageListActivity.EXTRA_FOLDER_ID, folder.id());
            intent.putExtra(MessageListActivity.EXTRA_FOLDER_NAME, folder.name());
            startActivity(intent);
        }
    }
}
