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

        RecyclerView list = findViewById(R.id.message_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new UnifiedMessageAdapter(this::openMessage);
        list.setAdapter(adapter);
        emptyView = findViewById(R.id.message_empty);

        findViewById(R.id.message_compose).setOnClickListener(view -> compose());
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        context.io().execute(() -> {
            List<Account> accounts = context.store().listAccounts();
            List<Message> allMessages = new ArrayList<>();
            
            for (Account account : accounts) {
                if (!account.enabled()) continue;
                List<Folder> folders = context.store().listFolders(account.id());
                for (Folder folder : folders) {
                    if (folder.type() == FolderType.INBOX) {
                        List<Message> messages = context.store()
                                .listMessages(account.id(), folder.id(), PAGE_SIZE, 0).messages();
                        // Add account info to message for display
                        for (Message msg : messages) {
                            // We could modify the snippet to show account, but for now just add all
                            allMessages.add(msg);
                        }
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
            
            runOnUiThread(() -> {
                adapter.submit(allMessages);
                emptyView.setVisibility(allMessages.isEmpty() ? View.VISIBLE : View.GONE);
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