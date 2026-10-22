/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.graalmail.android.AppContext;
import com.graalmail.android.R;
import com.graalmail.android.work.SyncScheduler;
import com.graalmail.core.domain.Account;

import java.util.List;

/**
 * The settings screen of the Android client.
 */
public final class SettingsActivity extends AppCompatActivity {

    /** The key of the sync frequency setting. */
    public static final String SETTING_SYNC_INTERVAL = "android.syncIntervalMinutes";
    /** The key of the notification setting. */
    public static final String SETTING_NOTIFICATIONS = "android.notifications";
    /** The key of the remote image setting. */
    public static final String SETTING_REMOTE_IMAGES = "android.loadRemoteImages";

    private AppContext context;
    private RadioGroup syncGroup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        context = AppContext.of(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.settings_title);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(view -> finish());

        syncGroup = findViewById(R.id.settings_sync_group);
        syncGroup.check(radioFor(interval()));
        findViewById(R.id.settings_save).setOnClickListener(view -> save());
        findViewById(R.id.settings_diagnostics).setOnClickListener(view ->
                startActivity(new Intent(this, DiagnosticsActivity.class)));
        findViewById(R.id.settings_remove_account).setOnClickListener(view -> removeAccount());
        renderAccounts();
    }

    private int interval() {
        String raw = context.store().setting(SETTING_SYNC_INTERVAL, "15");
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 15;
        }
    }

    private int radioFor(int minutes) {
        if (minutes <= 15) {
            return R.id.settings_sync_15;
        }
        if (minutes <= 30) {
            return R.id.settings_sync_30;
        }
        if (minutes <= 60) {
            return R.id.settings_sync_60;
        }
        return R.id.settings_sync_180;
    }

    private void save() {
        int checked = syncGroup.getCheckedRadioButtonId();
        int minutes = checked == R.id.settings_sync_15 ? 15
                : checked == R.id.settings_sync_30 ? 30
                : checked == R.id.settings_sync_60 ? 60 : 180;
        context.store().putSetting(SETTING_SYNC_INTERVAL, Integer.toString(minutes));
        SyncScheduler.schedule(this, context);
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
    }

    private void renderAccounts() {
        TextView accounts = findViewById(R.id.settings_accounts);
        List<Account> configured = context.store().listAccounts();
        if (configured.isEmpty()) {
            accounts.setText(R.string.diagnostics_no_accounts);
            findViewById(R.id.settings_remove_account).setVisibility(View.GONE);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Account account : configured) {
            sb.append(account.email()).append('\n');
        }
        accounts.setText(sb.toString().trim());
    }

    private void removeAccount() {
        List<Account> configured = context.store().listAccounts();
        if (configured.isEmpty()) {
            return;
        }
        Account account = configured.get(0);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.settings_remove_account)
                .setMessage(getString(R.string.settings_remove_account_confirm, account.email()))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_ok, (dialog, which) -> {
                    context.io().execute(() -> {
                        context.sessions().deleteCredential(account);
                        context.store().deleteAccount(account.id());
                        runOnUiThread(this::renderAccounts);
                    });
                    Toast.makeText(this, R.string.settings_account_removed, Toast.LENGTH_SHORT).show();
                })
                .show();
    }
}
