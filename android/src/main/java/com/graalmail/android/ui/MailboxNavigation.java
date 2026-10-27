/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.ui;

import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.graalmail.android.AppContext;
import com.graalmail.android.R;
import com.graalmail.core.domain.Account;
import com.graalmail.core.domain.Folder;
import com.graalmail.core.domain.FolderType;

import java.util.List;

/** Shared account and folder navigation used by the mailbox screens. */
final class MailboxNavigation implements FolderAdapter.OnFolderSelected {
    private final AppCompatActivity activity;
    private final AppContext context;
    private final DrawerLayout drawer;
    private final FolderAdapter adapter;
    private final boolean showingUnifiedInbox;
    private final String currentAccountId;
    private final String currentFolderId;

    MailboxNavigation(AppCompatActivity activity, DrawerLayout drawer, boolean showingUnifiedInbox,
                      String currentAccountId, String currentFolderId) {
        this.activity = activity;
        this.context = AppContext.of(activity);
        this.drawer = drawer;
        this.showingUnifiedInbox = showingUnifiedInbox;
        this.currentAccountId = currentAccountId;
        this.currentFolderId = currentFolderId;

        RecyclerView list = activity.findViewById(R.id.drawer_mailboxes);
        list.setLayoutManager(new LinearLayoutManager(activity));
        adapter = new FolderAdapter(activity, this);
        list.setAdapter(adapter);
        reload();

        View addAccount = activity.findViewById(R.id.drawer_add_account);
        addAccount.setOnClickListener(view -> {
            drawer.closeDrawer(GravityCompat.START);
            activity.startActivity(new Intent(activity, AccountSetupActivity.class));
        });
        View settings = activity.findViewById(R.id.drawer_settings);
        settings.setOnClickListener(view -> {
            drawer.closeDrawer(GravityCompat.START);
            activity.startActivity(new Intent(activity, SettingsActivity.class));
        });

        activity.getOnBackPressedDispatcher().addCallback(activity, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawer.isDrawerOpen(GravityCompat.START)) {
                    drawer.closeDrawer(GravityCompat.START);
                    return;
                }
                setEnabled(false);
                activity.getOnBackPressedDispatcher().onBackPressed();
            }
        });
    }

    void reload() {
        List<Account> accounts = context.store().listAccounts();
        adapter.submit(accounts, (account, ignored) -> context.store().listFolders(account.id()));
        adapter.setSelectedMailbox(showingUnifiedInbox, currentAccountId, currentFolderId);
        TextView summary = activity.findViewById(R.id.drawer_account_summary);
        if (accounts.size() == 1) {
            summary.setText(accounts.get(0).email());
        } else {
            summary.setText(activity.getString(R.string.mailbox_navigation_account_count, accounts.size()));
        }
    }

    void open() {
        drawer.openDrawer(GravityCompat.START);
    }

    @Override
    public void onFolderSelected(Account account, Folder folder) {
        drawer.closeDrawer(GravityCompat.START);
        boolean unified = folder.type() == FolderType.UNIFIED_INBOX;
        if (unified == showingUnifiedInbox && (unified ||
                (account.id().equals(currentAccountId) && folder.id().equals(currentFolderId)))) {
            return;
        }

        Intent intent;
        if (unified) {
            intent = new Intent(activity, UnifiedInboxActivity.class);
        } else {
            intent = new Intent(activity, MessageListActivity.class);
            intent.putExtra(MessageListActivity.EXTRA_ACCOUNT_ID, account.id());
            intent.putExtra(MessageListActivity.EXTRA_FOLDER_ID, folder.id());
            intent.putExtra(MessageListActivity.EXTRA_FOLDER_NAME, folder.name());
        }
        activity.startActivity(intent);
        activity.finish();
    }
}
