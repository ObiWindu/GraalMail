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

    private static final int TYPE_ACCOUNT = 1;
    private static final int TYPE_FOLDER = 2;

    private final List<Entry> entries = new ArrayList<>();
    private final OnFolderSelected listener;

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
        return entries.get(position).kind() == Kind.ACCOUNT ? TYPE_ACCOUNT : TYPE_FOLDER;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_ACCOUNT) {
            return new AccountHolder(inflater.inflate(R.layout.item_account, parent, false));
        }
        return new FolderHolder(inflater.inflate(R.layout.item_folder, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Entry entry = entries.get(position);
        if (holder instanceof AccountHolder accountHolder) {
            accountHolder.bind(entry.account());
        } else if (holder instanceof FolderHolder folderHolder) {
            folderHolder.bind(entry.account(), entry.folder(), listener);
        }
    }

    @Override
    public int getItemCount() {
        return entries.size();
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
