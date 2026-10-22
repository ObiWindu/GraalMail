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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mailbox_list);
        context = AppContext.of(this);
        NotificationHelper.createChannel(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        RecyclerView list = findViewById(R.id.mailbox_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FolderAdapter(this);
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
        adapter.submit(accounts, (account, accountId) -> context.store().listFolders(accountId));
        boolean empty = accounts.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        findViewById(R.id.mailbox_add).setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void synchronise() {
        refresh.setRefreshing(true);
        context.io().execute(() -> {
            int newMessages = 0;
            String failure = null;
            try {
                List<SyncReport> reports = context.syncEngine().syncAll();
                for (SyncReport report : reports) {
                    newMessages += report.newMessages();
                    if (!report.successful() && failure == null) {
                        failure = report.errors().isEmpty() ? "" : report.errors().get(0);
                    }
                }
            } catch (RuntimeException e) {
                LOG.warn("The synchronisation failed: {}", e.getMessage());
                failure = e.getMessage();
            }
            int count = newMessages;
            String message = failure;
            runOnUiThread(() -> {
                refresh.setRefreshing(false);
                reload();
                if (count > 0) {
                    NotificationHelper.notifyNewMail(this, count);
                }
                if (message != null && !message.isEmpty()) {
                    android.widget.Toast.makeText(this,
                            getString(R.string.mailbox_sync_failed, message),
                            android.widget.Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    @Override
    public void onFolderSelected(Account account, Folder folder) {
        Intent intent = new Intent(this, MessageListActivity.class);
        intent.putExtra(MessageListActivity.EXTRA_ACCOUNT_ID, account.id());
        intent.putExtra(MessageListActivity.EXTRA_FOLDER_ID, folder.id());
        intent.putExtra(MessageListActivity.EXTRA_FOLDER_NAME, folder.name());
        startActivity(intent);
    }
}
