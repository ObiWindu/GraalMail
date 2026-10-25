/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.appcompat.widget.SearchView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.graalmail.android.AppContext;
import com.graalmail.android.R;
import com.graalmail.core.domain.Account;
import com.graalmail.core.domain.Folder;
import com.graalmail.core.domain.FolderType;
import com.graalmail.core.domain.Message;
import com.graalmail.core.domain.SearchQuery;
import com.graalmail.core.text.TextTools;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.HashMap;

/**
 * Shows a unified inbox with messages from all account inboxes.
 */
public final class UnifiedInboxActivity extends AppCompatActivity {

    private static final int PAGE_SIZE = 50;

    private AppContext context;
    private UnifiedMessageAdapter adapter;
    private TextView emptyView;
    private TextView pagingStatus;
    private RecyclerView messageList;
    private int pageOffset;
    private String activeQuery = "";
    private int generation;
    private boolean loading;
    private boolean canLoadMore = true;
    private static final String STATE_QUERY = "unified_inbox.query";
    private static final String STATE_PAGE_OFFSET = "unified_inbox.pageOffset";
    private static final String STATE_UNREAD = "unified_inbox.unread";
    private static final String STATE_STARRED = "unified_inbox.starred";
    private static final String STATE_ATTACHMENTS = "unified_inbox.attachments";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_message_list);
        EdgeToEdge.apply(this);
        context = AppContext.of(this);
        if (savedInstanceState != null) {
            activeQuery = savedInstanceState.getString(STATE_QUERY, "");
            pageOffset = savedInstanceState.getInt(STATE_PAGE_OFFSET, 0);
            ((com.google.android.material.chip.Chip) findViewById(R.id.filter_unread))
                    .setChecked(savedInstanceState.getBoolean(STATE_UNREAD));
            ((com.google.android.material.chip.Chip) findViewById(R.id.filter_starred))
                    .setChecked(savedInstanceState.getBoolean(STATE_STARRED));
            ((com.google.android.material.chip.Chip) findViewById(R.id.filter_attachments))
                    .setChecked(savedInstanceState.getBoolean(STATE_ATTACHMENTS));
        }

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.mailbox_unified_inbox);
        setSupportActionBar(toolbar);
        toolbar.inflateMenu(R.menu.menu_message_list);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(view -> finish());

        messageList = findViewById(R.id.message_list);
        messageList.setLayoutManager(new LinearLayoutManager(this));
        adapter = new UnifiedMessageAdapter(this::openMessage);
        messageList.setAdapter(adapter);
        messageList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                var layout = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (dy > 0 && canLoadMore && !loading && layout != null
                        && layout.findLastVisibleItemPosition() >= adapter.getItemCount() - 8) load(true);
            }
        });
        emptyView = findViewById(R.id.message_empty);
        pagingStatus = findViewById(R.id.message_paging_status);
        MenuItem searchItem = toolbar.getMenu().findItem(R.id.action_search_messages);
        SearchView search = (SearchView) searchItem.getActionView();
        search.setQueryHint(getString(R.string.message_list_search_hint));
        search.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override public boolean onQueryTextSubmit(String query) { activeQuery = query; pageOffset = 0; load(false); search.clearFocus(); return true; }
            @Override public boolean onQueryTextChange(String query) {
                if (query.isBlank()) { activeQuery = ""; pageOffset = 0; load(false); }
                return false;
            }
        });
        if (!activeQuery.isBlank()) {
            searchItem.expandActionView();
            search.setQuery(activeQuery, false);
        }

        android.widget.CompoundButton.OnCheckedChangeListener filterListener = (button, checked) -> {
            pageOffset = 0;
            load(false);
        };
        ((com.google.android.material.chip.Chip) findViewById(R.id.filter_unread)).setOnCheckedChangeListener(filterListener);
        ((com.google.android.material.chip.Chip) findViewById(R.id.filter_starred)).setOnCheckedChangeListener(filterListener);
        ((com.google.android.material.chip.Chip) findViewById(R.id.filter_attachments)).setOnCheckedChangeListener(filterListener);
        findViewById(R.id.filter_clear).setOnClickListener(view -> {
            ((com.google.android.material.chip.Chip) findViewById(R.id.filter_unread)).setChecked(false);
            ((com.google.android.material.chip.Chip) findViewById(R.id.filter_starred)).setChecked(false);
            ((com.google.android.material.chip.Chip) findViewById(R.id.filter_attachments)).setChecked(false);
        });

        findViewById(R.id.message_compose).setOnClickListener(view -> compose());
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putString(STATE_QUERY, activeQuery);
        outState.putInt(STATE_PAGE_OFFSET, pageOffset);
        outState.putBoolean(STATE_UNREAD,
                ((com.google.android.material.chip.Chip) findViewById(R.id.filter_unread)).isChecked());
        outState.putBoolean(STATE_STARRED,
                ((com.google.android.material.chip.Chip) findViewById(R.id.filter_starred)).isChecked());
        outState.putBoolean(STATE_ATTACHMENTS,
                ((com.google.android.material.chip.Chip) findViewById(R.id.filter_attachments)).isChecked());
        super.onSaveInstanceState(outState);
    }

    private void load() {
        load(false);
    }

    private void load(boolean append) {
        if (append && loading) return;
        loading = true;
        if (!append) {
            generation++;
            canLoadMore = true;
        }
        int currentGeneration = generation;
        int offset = pageOffset;
        int fetchLimit = append ? offset + PAGE_SIZE + 1 : Math.max(offset + PAGE_SIZE, PAGE_SIZE) + 1;
        String query = activeQuery;
        boolean unreadOnly = ((com.google.android.material.chip.Chip) findViewById(R.id.filter_unread)).isChecked();
        boolean flaggedOnly = ((com.google.android.material.chip.Chip) findViewById(R.id.filter_starred)).isChecked();
        boolean attachmentsOnly = ((com.google.android.material.chip.Chip) findViewById(R.id.filter_attachments)).isChecked();
        boolean filtered = unreadOnly || flaggedOnly || attachmentsOnly;
        findViewById(R.id.filter_clear).setVisibility(filtered ? View.VISIBLE : View.GONE);
        if (append) {
            pagingStatus.setText(R.string.message_list_loading_more);
            pagingStatus.setOnClickListener(null);
            pagingStatus.setVisibility(View.VISIBLE);
        } else {
            pagingStatus.setVisibility(View.GONE);
        }
        context.io().execute(() -> {
            try {
            List<Account> accounts = context.store().listAccounts();
            Map<String, String> accountLabels = new HashMap<>();
            List<Message> allMessages = new ArrayList<>();
            
            for (Account account : accounts) {
                if (!account.enabled()) continue;
                accountLabels.put(account.id(), account.displayName().isBlank()
                        ? account.email() : account.displayName());
                List<Folder> folders = context.store().listFolders(account.id());
                for (Folder folder : folders) {
                    if (folder.type() == FolderType.INBOX) {
                        List<Message> messages;
                        if (query.isBlank() && !filtered) {
                            messages = context.store().listMessages(account.id(), folder.id(),
                                    fetchLimit, 0).messages();
                        } else {
                            messages = context.store().search(SearchQuery.builder().text(query)
                                    .accountIds(List.of(account.id())).folderIds(List.of(folder.id()))
                                    .unreadOnly(unreadOnly).flaggedOnly(flaggedOnly)
                                    .withAttachmentsOnly(attachmentsOnly)
                                    .limit(fetchLimit).build(), fetchLimit);
                        }
                        allMessages.addAll(messages);
                    }
                }
            }
            
            allMessages.sort((a, b) -> {
                if (a.date() == null) return b.date() == null ? 0 : 1;
                if (b.date() == null) return -1;
                return b.date().compareTo(a.date());
            });
            int from = Math.min(append ? offset : 0, allMessages.size());
            int to = Math.min(offset + PAGE_SIZE, allMessages.size());
            List<Message> page = new ArrayList<>(allMessages.subList(from, to));
            runOnUiThread(() -> {
                if (currentGeneration == generation) {
                    adapter.setAccountLabels(accountLabels);
                    if (append) adapter.append(page); else adapter.submit(page);
                    pageOffset = to;
                    canLoadMore = append ? page.size() >= PAGE_SIZE : to >= offset + PAGE_SIZE;
                    boolean empty = !append && page.isEmpty();
                    emptyView.setText(query.isBlank()
                            ? (filtered ? R.string.message_list_empty_filters : R.string.message_list_empty)
                            : R.string.message_list_empty_search);
                    emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
                    loading = false;
                    pagingStatus.setVisibility(View.GONE);
                }
            });
            } catch (RuntimeException e) {
                runOnUiThread(() -> {
                    if (currentGeneration == generation) {
                        loading = false;
                        pagingStatus.setText(R.string.message_list_load_failed);
                        pagingStatus.setOnClickListener(view -> load(true));
                        pagingStatus.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    private void openMessage(Message message) {
        // Resolve the account stored on the message, including in the unified inbox.
        context.io().execute(() -> {
            Optional<Account> account = context.store().account(message.accountId());
            if (account.isPresent()) {
                runOnUiThread(() -> {
                    Intent intent = new Intent(this, MessageViewActivity.class);
                    intent.putExtra(MessageViewActivity.EXTRA_ACCOUNT_ID, account.get().id());
                    intent.putExtra(MessageViewActivity.EXTRA_MESSAGE_ID, message.id());
                    startActivity(intent);
                });
            }
        });
    }

    private void compose() {
        // Let user select account
        Intent intent = new Intent(this, ComposeActivity.class);
        // Don't set account ID, let ComposeActivity pick default
        startActivity(intent);
    }

    /**
     * The adapter for unified inbox messages.
     */
    static final class UnifiedMessageAdapter extends RecyclerView.Adapter<UnifiedMessageAdapter.Holder> {

        private final List<Message> messages = new ArrayList<>();
        private final OnMessageSelected listener;
        private Map<String, String> accountLabels = Map.of();

        UnifiedMessageAdapter(OnMessageSelected listener) {
            this.listener = listener;
        }

        void submit(List<Message> values) {
            messages.clear();
            messages.addAll(values);
            notifyDataSetChanged();
        }

        void append(List<Message> values) {
            int start = messages.size();
            messages.addAll(values);
            notifyItemRangeInserted(start, values.size());
        }

        void setAccountLabels(Map<String, String> labels) {
            accountLabels = new HashMap<>(labels);
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_message, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.bind(messages.get(position), accountLabels.get(messages.get(position).accountId()), listener);
        }

        @Override
        public int getItemCount() {
            return messages.size();
        }

        interface OnMessageSelected {
            void onMessageSelected(Message message);
        }

        static final class Holder extends RecyclerView.ViewHolder {
            private final TextView sender;
            private final TextView subject;
            private final TextView snippet;
            private final TextView date;
            private final TextView accountBadge;
            private final View unreadDot;

            Holder(View view) {
                super(view);
                sender = view.findViewById(R.id.message_sender);
                subject = view.findViewById(R.id.message_subject);
                snippet = view.findViewById(R.id.message_snippet);
                date = view.findViewById(R.id.message_date);
                accountBadge = view.findViewById(R.id.message_account_badge);
                unreadDot = view.findViewById(R.id.message_unread_dot);
            }

            void bind(Message message, String accountLabel, OnMessageSelected listener) {
                sender.setText(message.from().displayLabel());
                unreadDot.setVisibility(message.unread() ? View.VISIBLE : View.GONE);
                subject.setText(message.subject());
                snippet.setText(TextTools.snippet(
                        message.snippet().isEmpty() ? message.bodyText() : message.snippet(), 120));
                date.setText(UiDateFormatter.shortDateTime(message.date()));
                sender.setTypeface(null, message.unread()
                        ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                subject.setTypeface(null, message.unread()
                        ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                
                if (accountLabel == null || accountLabel.isBlank()) {
                    accountBadge.setVisibility(View.GONE);
                } else {
                    accountBadge.setText(accountLabel);
                    accountBadge.setVisibility(View.VISIBLE);
                }
                itemView.setContentDescription(message.from().displayLabel() + ". "
                        + (message.subject() == null ? "" : message.subject()) + ". "
                        + TextTools.snippet(message.snippet().isEmpty()
                                ? message.bodyText() : message.snippet(), 120)
                        + (accountLabel == null ? "" : ". " + accountLabel)
                        + (message.unread() ? ". " + itemView.getResources().getString(
                                R.string.message_list_unread_indicator) : "")
                        + (message.flagged() ? ". " + itemView.getResources().getString(
                                R.string.message_list_flagged_indicator) : "")
                        + (!message.attachments().isEmpty() ? ". " + itemView.getResources().getString(
                                R.string.message_list_attachment_indicator) : ""));
                
                itemView.setOnClickListener(view -> {
                    if (listener != null) {
                        listener.onMessageSelected(message);
                    }
                });
            }
        }
    }
}
