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
import android.widget.LinearLayout;
import android.view.Gravity;

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
    private RadioGroup themeGroup;
    private com.google.android.material.materialswitch.MaterialSwitch notificationsSwitch;
    private int originalInterval;
    private String originalTheme;
    private boolean originalNotifications;
    private View saveButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        EdgeToEdge.apply(this);
        context = AppContext.of(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.settings_title);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(view -> closeSettings());

        syncGroup = findViewById(R.id.settings_sync_group);
        originalInterval = interval();
        syncGroup.check(radioFor(originalInterval));
        themeGroup = findViewById(R.id.settings_theme_group);
        originalTheme = context.store().setting("android.theme", "system");
        themeGroup.check(themeRadio(originalTheme));
        notificationsSwitch = findViewById(R.id.settings_notifications_enabled);
        originalNotifications = Boolean.parseBoolean(context.store().setting(SETTING_NOTIFICATIONS, "true"));
        notificationsSwitch.setChecked(originalNotifications);
        saveButton = findViewById(R.id.settings_save);
        saveButton.setEnabled(false);
        syncGroup.setOnCheckedChangeListener((group, checkedId) -> updateSaveState());
        themeGroup.setOnCheckedChangeListener((group, checkedId) -> updateSaveState());
        notificationsSwitch.setOnCheckedChangeListener((button, checked) -> updateSaveState());
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { closeSettings(); }
        });
        saveButton.setOnClickListener(view -> save());
        findViewById(R.id.settings_diagnostics).setOnClickListener(view ->
                startActivity(new Intent(this, DiagnosticsActivity.class)));
        renderAccounts();
    }

    private boolean isDirty() {
        return intervalFor(syncGroup.getCheckedRadioButtonId()) != originalInterval
                || !currentTheme().equals(originalTheme)
                || notificationsSwitch.isChecked() != originalNotifications;
    }

    private int intervalFor(int checked) {
        return checked == R.id.settings_sync_15 ? 15
                : checked == R.id.settings_sync_30 ? 30
                : checked == R.id.settings_sync_60 ? 60 : 180;
    }

    private String currentTheme() {
        int checked = themeGroup.getCheckedRadioButtonId();
        return checked == R.id.settings_theme_light ? "light"
                : checked == R.id.settings_theme_dark ? "dark" : "system";
    }

    private void updateSaveState() {
        saveButton.setEnabled(isDirty());
    }

    private void closeSettings() {
        if (!isDirty()) {
            finish();
            return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.settings_title)
                .setMessage(R.string.settings_discard_confirm)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_discard, (dialog, which) -> finish())
                .show();
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
        int minutes = intervalFor(syncGroup.getCheckedRadioButtonId());
        context.store().putSetting(SETTING_SYNC_INTERVAL, Integer.toString(minutes));
        String theme = currentTheme();
        context.store().putSetting("android.theme", theme);
        context.store().putSetting(SETTING_NOTIFICATIONS, Boolean.toString(notificationsSwitch.isChecked()));
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode("light".equals(theme)
                ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                : "dark".equals(theme) ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        SyncScheduler.schedule(this, context);
        originalInterval = minutes;
        originalTheme = theme;
        originalNotifications = notificationsSwitch.isChecked();
        updateSaveState();
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
    }

    private int themeRadio(String theme) {
        return "light".equals(theme) ? R.id.settings_theme_light
                : "dark".equals(theme) ? R.id.settings_theme_dark : R.id.settings_theme_system;
    }

    private void renderAccounts() {
        LinearLayout accounts = findViewById(R.id.settings_accounts_list);
        accounts.removeAllViews();
        List<Account> configured = context.store().listAccounts();
        if (configured.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.diagnostics_no_accounts);
            int inset = getResources().getDimensionPixelSize(R.dimen.space_3);
            empty.setPadding(0, inset, 0, inset);
            accounts.addView(empty);
            return;
        }
        for (Account account : configured) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int inset = getResources().getDimensionPixelSize(R.dimen.space_1);
            row.setPadding(0, inset, 0, inset);
            TextView email = new TextView(this);
            email.setText(account.displayName().isBlank() || account.displayName().equals(account.email())
                    ? account.email() : account.displayName() + "\n" + account.email());
            email.setTextAppearance(R.style.TextAppearance_GraalMail_Account);
            email.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            com.google.android.material.button.MaterialButton remove =
                    new com.google.android.material.button.MaterialButton(this);
            remove.setTextAppearance(R.style.TextAppearance_GraalMail_Button);
            remove.setText(R.string.settings_remove_account);
            remove.setOnClickListener(view -> removeAccount(account));
            row.addView(email);
            row.addView(remove);
            accounts.addView(row);
        }
    }

    private void removeAccount(Account account) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.settings_remove_account)
                .setMessage(getString(R.string.settings_remove_account_confirm, account.email()))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_ok, (dialog, which) -> {
                    // Report success only after the account and credential have been removed.
                    context.io().execute(() -> {
                        context.sessions().deleteCredential(account);
                        context.store().deleteAccount(account.id());
                        runOnUiThread(() -> {
                            SyncScheduler.schedule(this, context);
                            renderAccounts();
                            Toast.makeText(this, R.string.settings_account_removed, Toast.LENGTH_SHORT).show();
                        });
                    });
                })
                .show();
    }
}
