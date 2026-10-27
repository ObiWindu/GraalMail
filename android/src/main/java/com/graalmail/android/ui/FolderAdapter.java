/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.DiffUtil;
import com.google.android.material.color.MaterialColors;

import com.graalmail.android.R;
import com.graalmail.core.domain.Account;
import com.graalmail.core.domain.Folder;
import com.graalmail.core.domain.FolderType;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

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
    private final SharedPreferences preferences;
    private final Set<String> collapsed = new HashSet<>();
    private final Map<String, List<Folder>> folderSnapshot = new HashMap<>();
    private boolean showUnifiedInbox;
    private boolean unifiedInboxSelected;
    private String selectedAccountId;
    private String selectedFolderId;

    /**
     * Creates the adapter.
     *
     * @param listener the callback for the opened mailbox
     */
    public FolderAdapter(Context context, OnFolderSelected listener) {
        this.listener = listener;
        preferences = context.getSharedPreferences("mailbox_ui", Context.MODE_PRIVATE);
        collapsed.addAll(preferences.getStringSet("collapsed_accounts", Set.of()));
        setHasStableIds(true);
    }

    /**
     * Replaces the content of the list.
     *
     * @param accounts the accounts with their mailboxes
     */
    public void submit(List<Account> accounts, java.util.function.BiFunction<Account, String, List<Folder>> folders) {
        List<Entry> replacement = new ArrayList<>();
        folderSnapshot.clear();
        showUnifiedInbox = !accounts.isEmpty();

        if (showUnifiedInbox) {
            // Add unified inbox entry
            replacement.add(new Entry(Kind.UNIFIED_INBOX, null,
                    new Folder("unified_inbox", "Unified Inbox", "", FolderType.UNIFIED_INBOX, 0, 0, true, false, "")));
        }

        for (Account account : accounts) {
            replacement.add(new Entry(Kind.ACCOUNT, account, null));
            if (!collapsed.contains(account.id())) {
                List<Folder> accountFolders = new ArrayList<>(folders.apply(account, account.id()));
                folderSnapshot.put(account.id(), new ArrayList<>(accountFolders));
                accountFolders.sort(Comparator.comparingInt((Folder folder) -> folderOrder(folder.type()))
                        .thenComparing(Folder::name, String.CASE_INSENSITIVE_ORDER));
                for (Folder folder : accountFolders) {
                    replacement.add(new Entry(Kind.FOLDER, account, folder));
                }
            }
            else folderSnapshot.put(account.id(), new ArrayList<>(folders.apply(account, account.id())));
        }
        List<Entry> previous = new ArrayList<>(entries);
        entries.clear();
        entries.addAll(replacement);
        DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return previous.size(); }
            @Override public int getNewListSize() { return replacement.size(); }
            @Override public boolean areItemsTheSame(int oldPos, int newPos) {
                return rowId(previous.get(oldPos)).equals(rowId(replacement.get(newPos)));
            }
            @Override public boolean areContentsTheSame(int oldPos, int newPos) {
                Entry old = previous.get(oldPos), current = replacement.get(newPos);
                if (old.kind() != current.kind()) return false;
                if (old.kind() == Kind.ACCOUNT) return false;
                if (old.kind() == Kind.FOLDER) {
                    return old.folder().name().equals(current.folder().name())
                            && old.folder().unreadCount() == current.folder().unreadCount();
                }
                return true;
            }
        }).dispatchUpdatesTo(this);
    }

    void setSelectedMailbox(boolean unifiedInbox, String accountId, String folderId) {
        unifiedInboxSelected = unifiedInbox;
        selectedAccountId = accountId;
        selectedFolderId = folderId;
        notifyDataSetChanged();
    }

    private static int folderOrder(FolderType type) {
        return switch (type) {
            case INBOX -> 0;
            case SENT -> 1;
            case DRAFTS -> 2;
            case ARCHIVE -> 3;
            case ALL -> 4;
            case SPAM -> 5;
            case TRASH -> 6;
            case OUTBOX -> 7;
            case CUSTOM -> 8;
            case UNIFIED_INBOX -> 9;
        };
    }

    private static String rowId(Entry entry) {
        return switch (entry.kind()) {
            case UNIFIED_INBOX -> "unified";
            case ACCOUNT -> "account:" + entry.account().id();
            case FOLDER -> "folder:" + entry.account().id() + ":" + entry.folder().id();
        };
    }

    @Override
    public long getItemId(int position) {
        return rowId(entries.get(position)).hashCode();
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
            unifiedHolder.bind(listener, unifiedInboxSelected);
        } else if (holder instanceof AccountHolder accountHolder) {
            accountHolder.bind(entry.account(), collapsed.contains(entry.account().id()), () -> toggle(entry.account().id()));
        } else if (holder instanceof FolderHolder folderHolder) {
            folderHolder.bind(entry.account(), entry.folder(), listener,
                    entry.account().id().equals(selectedAccountId)
                            && entry.folder().id().equals(selectedFolderId));
        }
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    private void toggle(String accountId) {
        if (!collapsed.add(accountId)) collapsed.remove(accountId);
        preferences.edit().putStringSet("collapsed_accounts", new HashSet<>(collapsed)).apply();
        // Rebuild from the last submitted snapshot while preserving its account/folder source.
        List<Account> accounts = new ArrayList<>();
        for (Entry entry : entries) if (entry.kind() == Kind.ACCOUNT) accounts.add(entry.account());
        Map<String, List<Folder>> snapshot = new HashMap<>(folderSnapshot);
        submit(accounts, (account, id) -> snapshot.getOrDefault(id, List.of()));
    }

    /** Shows the unified inbox entry. */
    static final class UnifiedInboxHolder extends RecyclerView.ViewHolder {
        private final TextView name;
        private final TextView summary;
        private final TextView count;

        UnifiedInboxHolder(View view) {
            super(view);
            name = view.findViewById(R.id.folder_name);
            summary = view.findViewById(R.id.folder_summary);
            count = view.findViewById(R.id.folder_count);
        }

        void bind(OnFolderSelected listener, boolean selected) {
            name.setText(R.string.mailbox_unified_inbox);
            summary.setText(R.string.mailbox_unified_summary);
            count.setText("");
            itemView.setActivated(selected);
            int contentColor = MaterialColors.getColor(itemView, selected
                    ? com.google.android.material.R.attr.colorOnSecondaryContainer
                    : com.google.android.material.R.attr.colorOnSurface);
            name.setTextColor(contentColor);
            summary.setTextColor(MaterialColors.getColor(itemView,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
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
        private final ImageButton expand;

        AccountHolder(View view) {
            super(view);
            name = view.findViewById(R.id.account_name);
            expand = view.findViewById(R.id.account_expand);
        }

        void bind(Account account, boolean isCollapsed, Runnable toggle) {
            String display = account.displayName().isBlank() ? account.email() : account.displayName();
            name.setText(display.equals(account.email()) ? display : display + "\n" + account.email());
            name.setContentDescription(display.equals(account.email()) ? account.email()
                    : display + ", " + account.email());
            expand.setContentDescription(itemView.getResources().getString(
                    isCollapsed ? R.string.mailbox_expand_account : R.string.mailbox_collapse_account));
            expand.setRotation(isCollapsed ? -90f : 0f);
            expand.setOnClickListener(view -> toggle.run());
            itemView.setOnClickListener(view -> toggle.run());
        }
    }

    /** Shows a mailbox with its unread count. */
    static final class FolderHolder extends RecyclerView.ViewHolder {
        private final ImageView icon;
        private final TextView name;
        private final TextView count;

        FolderHolder(View view) {
            super(view);
            icon = view.findViewById(R.id.folder_icon);
            name = view.findViewById(R.id.folder_name);
            count = view.findViewById(R.id.folder_count);
        }

        void bind(Account account, Folder folder, OnFolderSelected listener, boolean selected) {
            int iconResource = switch (folder.type()) {
                case INBOX, ALL -> R.drawable.ic_folder_inbox;
                case SENT, OUTBOX -> R.drawable.ic_send;
                case DRAFTS -> R.drawable.ic_drafts;
                case ARCHIVE -> R.drawable.ic_archive;
                case TRASH -> R.drawable.ic_trash;
                case SPAM -> R.drawable.ic_spam;
                case CUSTOM -> R.drawable.ic_folder;
                case UNIFIED_INBOX -> R.drawable.ic_mail;
            };
            icon.setImageResource(iconResource);
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(MaterialColors.getColor(
                    itemView, selected ? com.google.android.material.R.attr.colorOnSecondaryContainer
                            : com.google.android.material.R.attr.colorOnSurfaceVariant)));
            name.setText(folder.name());
            name.setTextColor(MaterialColors.getColor(itemView, selected
                    ? com.google.android.material.R.attr.colorOnSecondaryContainer
                    : com.google.android.material.R.attr.colorOnSurface));
            count.setText(folder.unreadCount() > 0
                    ? String.valueOf(folder.unreadCount())
                    : "");
            count.setContentDescription(folder.unreadCount() > 0
                    ? itemView.getResources().getString(R.string.cd_unread_count, folder.unreadCount()) : null);
            itemView.setContentDescription(folder.name() + (folder.unreadCount() > 0
                    ? ", " + itemView.getResources().getString(R.string.cd_unread_count, folder.unreadCount()) : ""));
            itemView.setActivated(selected);
            itemView.setOnClickListener(view -> {
                if (listener != null) {
                    listener.onFolderSelected(account, folder);
                }
            });
        }
    }
}
