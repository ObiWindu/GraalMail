/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
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

/**
 * Shows a unified inbox with messages from all account inboxes.
 */
public final class UnifiedInboxActivity extends AppCompatActivity {

    private static final int PAGE_SIZE = 50;

    private AppContext context;
    private UnifiedMessageAdapter adapter;
    private TextView emptyView;
    private RecyclerView messageList;
    private int pageOffset;
    private String activeQuery = "";
    private int generation;
    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_message_list);
        context = AppContext.of(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.mailbox_unified_inbox);
        setSupportActionBar(toolbar);
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
                if (dy > 0 && !loading && layout != null
                        && layout.findLastVisibleItemPosition() >= adapter.getItemCount() - 8) load(true);
            }
        });
        emptyView = findViewById(R.id.message_empty);
        SearchView search = findViewById(R.id.search_view);
        search.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override public boolean onQueryTextSubmit(String query) { activeQuery = query; load(false); search.clearFocus(); return true; }
            @Override public boolean onQueryTextChange(String query) {
                if (query.isBlank()) { activeQuery = ""; load(false); }
                return false;
            }
        });

        findViewById(R.id.message_compose).setOnClickListener(view -> compose());
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        load(false);
    }

    private void load(boolean append) {
        if (loading) return;
        loading = true;
        if (!append) { pageOffset = 0; generation++; }
        int currentGeneration = generation;
        int offset = pageOffset;
        String query = activeQuery;
        context.io().execute(() -> {
            List<Account> accounts = context.store().listAccounts();
            List<Message> allMessages = new ArrayList<>();
            
            for (Account account : accounts) {
                if (!account.enabled()) continue;
                List<Folder> folders = context.store().listFolders(account.id());
                for (Folder folder : folders) {
                    if (folder.type() == FolderType.INBOX) {
                        List<Message> messages;
                        if (query.isBlank()) {
                            messages = context.store().listMessages(account.id(), folder.id(),
                                    offset + PAGE_SIZE + 1, 0).messages();
                        } else {
                            messages = context.store().search(SearchQuery.builder().text(query)
                                    .accountIds(List.of(account.id())).folderIds(List.of(folder.id()))
                                    .limit(offset + PAGE_SIZE + 1).build(), offset + PAGE_SIZE + 1);
                        }
                        allMessages.addAll(messages);
                    }
                }
            }
            
            // Sort by date descending
            allMessages.sort((a, b) -> {
                if (a.date() == null && b.date() == null) return 0;
                if (a.date() == null) return 1;
                if (b.date() == null) return -1;
                return b.date().compareTo(a.date());
            });
            
            allMessages.sort((a, b) -> {
                if (a.date() == null) return b.date() == null ? 0 : 1;
                if (b.date() == null) return -1;
                return b.date().compareTo(a.date());
            });
            int from = Math.min(offset, allMessages.size());
            int to = Math.min(offset + PAGE_SIZE, allMessages.size());
            List<Message> page = new ArrayList<>(allMessages.subList(from, to));
            runOnUiThread(() -> {
                if (currentGeneration == generation) {
                    if (append) adapter.append(page); else adapter.submit(page);
                    pageOffset = to;
                    boolean empty = !append && page.isEmpty();
                    emptyView.setText(query.isBlank() ? R.string.message_list_empty
                            : R.string.message_list_empty_search);
                    emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
                }
                loading = false;
            });
        });
    }

    private void openMessage(Message message) {
        // Find which account this message belongs to
        // For simplicity, we'll just open it with the first account that has this message
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

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_message, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.bind(messages.get(position), listener);
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

            Holder(View view) {
                super(view);
                sender = view.findViewById(R.id.message_sender);
                subject = view.findViewById(R.id.message_subject);
                snippet = view.findViewById(R.id.message_snippet);
                date = view.findViewById(R.id.message_date);
                accountBadge = view.findViewById(R.id.message_account_badge);
            }

            void bind(Message message, OnMessageSelected listener) {
                sender.setText(message.from().displayLabel());
                subject.setText(message.subject());
                snippet.setText(TextTools.snippet(
                        message.snippet().isEmpty() ? message.bodyText() : message.snippet(), 120));
                date.setText(message.date() == null ? "" : message.date().toString());
                subject.setTypeface(null, message.unread()
                        ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                
                // Show account badge if we can determine it
                // For now, we'll hide it since we don't have easy access to account email here
                accountBadge.setVisibility(View.GONE);
                
                itemView.setOnClickListener(view -> {
                    if (listener != null) {
                        listener.onMessageSelected(message);
                    }
                });
            }
        }
    }
}
