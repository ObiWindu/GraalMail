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
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.graalmail.android.AppContext;
import com.graalmail.android.R;
import com.graalmail.core.domain.Message;
import com.graalmail.core.domain.SearchQuery;
import com.graalmail.core.text.TextTools;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows the messages of a mailbox.
 *
 * <p>The list is filled from the local cache first, so that the screen is
 * usable without a network connection, and it is refreshed from the provider
 * in the background.</p>
 */
public final class MessageListActivity extends AppCompatActivity {

    /** The identifier of the account whose mailbox is shown. */
    public static final String EXTRA_ACCOUNT_ID = "accountId";
    /** The identifier of the shown mailbox. */
    public static final String EXTRA_FOLDER_ID = "folderId";
    /** The name of the shown mailbox. */
    public static final String EXTRA_FOLDER_NAME = "folderName";
    /** The identifier of the message that is opened. */
    public static final String EXTRA_MESSAGE_ID = "messageId";

    private static final int PAGE_SIZE = 50;

    private AppContext context;
    private String accountId;
    private String folderId;
    private MessageAdapter adapter;
    private TextView emptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_message_list);
        context = AppContext.of(this);

        accountId = getIntent().getStringExtra(EXTRA_ACCOUNT_ID);
        folderId = getIntent().getStringExtra(EXTRA_FOLDER_ID);
        String folderName = getIntent().getStringExtra(EXTRA_FOLDER_NAME);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(folderName == null ? getString(R.string.message_list_title) : folderName);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(view -> finish());

        SearchView searchView = findViewById(R.id.search_view);
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                search(query);
                searchView.clearFocus();
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                if (newText.isEmpty()) {
                    load();
                }
                return false;
            }
        });
        searchView.setOnCloseListener(() -> {
            load();
            return false;
        });

        RecyclerView list = findViewById(R.id.message_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MessageAdapter(this::openMessage);
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
            List<Message> messages = new ArrayList<>(context.store()
                    .listMessages(accountId, folderId, PAGE_SIZE, 0).messages());
            runOnUiThread(() -> {
                adapter.submit(messages);
                emptyView.setVisibility(messages.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private void search(String query) {
        context.io().execute(() -> {
            List<Message> messages = context.store()
                    .search(SearchQuery.builder().text(query).build(), PAGE_SIZE);
            runOnUiThread(() -> {
                adapter.submit(messages);
                emptyView.setVisibility(messages.isEmpty() ? View.VISIBLE : View.GONE);
                emptyView.setText(R.string.message_list_empty_search);
            });
        });
    }

    private void openMessage(Message message) {
        Intent intent = new Intent(this, MessageViewActivity.class);
        intent.putExtra(MessageViewActivity.EXTRA_ACCOUNT_ID, accountId);
        intent.putExtra(MessageViewActivity.EXTRA_MESSAGE_ID, message.id());
        startActivity(intent);
    }

    private void compose() {
        Intent intent = new Intent(this, ComposeActivity.class);
        intent.putExtra(ComposeActivity.EXTRA_ACCOUNT_ID, accountId);
        startActivity(intent);
    }

    /**
     * The adapter of the message list.
     */
    static final class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.Holder> {

        private final List<Message> messages = new ArrayList<>();
        private final OnMessageSelected listener;

        MessageAdapter(OnMessageSelected listener) {
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

        /** The callback that is called when a message is opened. */
        interface OnMessageSelected {
            void onMessageSelected(Message message);
        }

        /** One row of the list. */
        static final class Holder extends RecyclerView.ViewHolder {
            private final TextView sender;
            private final TextView subject;
            private final TextView snippet;
            private final TextView date;

            Holder(View view) {
                super(view);
                sender = view.findViewById(R.id.message_sender);
                subject = view.findViewById(R.id.message_subject);
                snippet = view.findViewById(R.id.message_snippet);
                date = view.findViewById(R.id.message_date);
            }

            void bind(Message message, OnMessageSelected listener) {
                sender.setText(message.from().displayLabel());
                subject.setText(message.subject());
                snippet.setText(TextTools.snippet(
                        message.snippet().isEmpty() ? message.bodyText() : message.snippet(), 120));
                date.setText(message.date() == null ? "" : message.date().toString());
                subject.setTypeface(null, message.unread()
                        ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                itemView.setOnClickListener(view -> {
                    if (listener != null) {
                        listener.onMessageSelected(message);
                    }
                });
            }
        }
    }
}
