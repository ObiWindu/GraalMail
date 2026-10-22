/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.graalmail.android.R;
import com.graalmail.core.domain.Account;
import com.graalmail.core.domain.Folder;
import com.graalmail.core.domain.FolderType;

import java.util.ArrayList;
import java.util.List;

/**
 * The adapter of the mailbox list.
 *
 * <p>The list shows the configured accounts as headers and their mailboxes as
 * entries below the header.</p>
 */
public final class FolderAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    /** The kind of a row in the list. */
    public enum Kind {
        /** The unified inbox entry. */
        UNIFIED_INBOX,
        /** The header of an account. */
        ACCOUNT,
        /** A mailbox of an account. */
        FOLDER
    }

    /** One row of the list. */
    public static final class Entry {
        private final Kind kind;
        private final Account account;
        private final Folder folder;

        Entry(Kind kind, Account account, Folder folder) {
            this.kind = kind;
            this.account = account;
            this.folder = folder;
        }

        public Kind kind() {
            return kind;
        }

        public Account account() {
            return account;
        }

        public Folder folder() {
            return folder;
        }
    }

    /** The callback that is called when a mailbox is opened. */
    public interface OnFolderSelected {
        /**
         * Called when the user opens a mailbox.
         *
         * @param account the account of the mailbox
         * @param folder the mailbox that was opened
         */
        void onFolderSelected(Account account, Folder folder);
    }

    private static final int TYPE_UNIFIED_INBOX = 0;
    private static final int TYPE_ACCOUNT = 1;
    private static final int TYPE_FOLDER = 2;

    private final List<Entry> entries = new ArrayList<>();
    private final OnFolderSelected listener;
    private boolean showUnifiedInbox;

    /**
     * Creates the adapter.
     *
     * @param listener the callback for the opened mailbox
     */
    public FolderAdapter(OnFolderSelected listener) {
        this.listener = listener;
    }

    /**
     * Replaces the content of the list.
     *
     * @param accounts the accounts with their mailboxes
     */
    public void submit(List<Account> accounts, java.util.function.BiFunction<Account, String, List<Folder>> folders) {
        entries.clear();
        showUnifiedInbox = accounts.size() > 1;

        if (showUnifiedInbox) {
            // Add unified inbox entry
            entries.add(new Entry(Kind.UNIFIED_INBOX, null,
                    new Folder("unified_inbox", "Unified Inbox", "", FolderType.UNIFIED_INBOX, 0, 0, true, false, "")));
        }

        for (Account account : accounts) {
            entries.add(new Entry(Kind.ACCOUNT, account, null));
            for (Folder folder : folders.apply(account, account.id())) {
                entries.add(new Entry(Kind.FOLDER, account, folder));
            }
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        Entry entry = entries.get(position);
        switch (entry.kind()) {
            case UNIFIED_INBOX: return TYPE_UNIFIED_INBOX;
            case ACCOUNT: return TYPE_ACCOUNT;
            case FOLDER: return TYPE_FOLDER;
            default: return TYPE_FOLDER;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_UNIFIED_INBOX) {
            return new UnifiedInboxHolder(inflater.inflate(R.layout.item_unified_inbox, parent, false));
        } else if (viewType == TYPE_ACCOUNT) {
            return new AccountHolder(inflater.inflate(R.layout.item_account, parent, false));
        }
        return new FolderHolder(inflater.inflate(R.layout.item_folder, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Entry entry = entries.get(position);
        if (holder instanceof UnifiedInboxHolder unifiedHolder) {
            unifiedHolder.bind(listener);
        } else if (holder instanceof AccountHolder accountHolder) {
            accountHolder.bind(entry.account());
        } else if (holder instanceof FolderHolder folderHolder) {
            folderHolder.bind(entry.account(), entry.folder(), listener);
        }
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    /** Shows the unified inbox entry. */
    static final class UnifiedInboxHolder extends RecyclerView.ViewHolder {
        private final TextView name;
        private final TextView count;

        UnifiedInboxHolder(View view) {
            super(view);
            name = view.findViewById(R.id.folder_name);
            count = view.findViewById(R.id.folder_count);
        }

        void bind(OnFolderSelected listener) {
            name.setText("Unified Inbox");
            count.setText("");
            itemView.setOnClickListener(view -> {
                if (listener != null) {
                    // Create a special unified inbox folder and account
                    listener.onFolderSelected(
                            Account.builder("unified", "unified").email("Unified").build(),
                            Folder.of("unified_inbox", "Unified Inbox", FolderType.UNIFIED_INBOX));
                }
            });
        }
    }

    /** Shows the account in the header row. */
    static final class AccountHolder extends RecyclerView.ViewHolder {
        private final TextView name;

        AccountHolder(View view) {
            super(view);
            name = view.findViewById(R.id.account_name);
        }

        void bind(Account account) {
            name.setText(account.displayName().isBlank() ? account.email() : account.displayName());
        }
    }

    /** Shows a mailbox with its unread count. */
    static final class FolderHolder extends RecyclerView.ViewHolder {
        private final TextView name;
        private final TextView count;

        FolderHolder(View view) {
            super(view);
            name = view.findViewById(R.id.folder_name);
            count = view.findViewById(R.id.folder_count);
        }

        void bind(Account account, Folder folder, OnFolderSelected listener) {
            name.setText(folder.name());
            count.setText(folder.unreadCount() > 0
                    ? String.valueOf(folder.unreadCount())
                    : "");
            itemView.setOnClickListener(view -> {
                if (listener != null) {
                    listener.onFolderSelected(account, folder);
                }
            });
        }
    }
}