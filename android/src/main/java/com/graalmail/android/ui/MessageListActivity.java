/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.graalmail.android.AppContext;
import com.graalmail.android.R;
import com.graalmail.core.domain.Message;
import com.graalmail.core.domain.SearchQuery;
import com.graalmail.core.domain.Folder;
import com.graalmail.core.domain.FolderType;
import com.graalmail.core.text.TextTools;
import com.graalmail.storage.PendingAction;

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
    private androidx.appcompat.view.ActionMode actionMode;
    private final List<Message> selectedMessages = new ArrayList<>();
    private int pageOffset;
    private boolean loadingMore;
    private String activeQuery = "";

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
        toolbar.setNavigationOnClickListener(view -> {
            if (actionMode != null) {
                actionMode.finish();
            } else {
                finish();
            }
        });

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
        adapter = new MessageAdapter(this::openMessage, this::onMessageLongClick);
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                var layout = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (dy > 0 && !loadingMore && layout != null
                        && layout.findLastVisibleItemPosition() >= adapter.getItemCount() - 8) loadMore();
            }
        });
        emptyView = findViewById(R.id.message_empty);

        findViewById(R.id.message_compose).setOnClickListener(view -> compose());
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        pageOffset = 0;
        activeQuery = "";
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
        activeQuery = query == null ? "" : query;
        pageOffset = 0;
        context.io().execute(() -> {
            List<Message> messages = context.store()
                    .search(SearchQuery.builder().text(query).accountIds(List.of(accountId))
                            .folderIds(folderId == null ? List.of() : List.of(folderId))
                            .limit(PAGE_SIZE).build(), PAGE_SIZE);
            runOnUiThread(() -> {
                adapter.submit(messages);
                pageOffset = messages.size();
                emptyView.setVisibility(messages.isEmpty() ? View.VISIBLE : View.GONE);
                emptyView.setText(R.string.message_list_empty_search);
            });
        });
    }

    private void loadMore() {
        loadingMore = true;
        int offset = pageOffset;
        String query = activeQuery;
        context.io().execute(() -> {
            List<Message> page = query.isBlank()
                    ? context.store().listMessages(accountId, folderId, PAGE_SIZE, offset).messages()
                    : context.store().search(SearchQuery.builder().text(query).accountIds(List.of(accountId))
                            .folderIds(folderId == null ? List.of() : List.of(folderId))
                            .limit(PAGE_SIZE).offset(offset).build(), PAGE_SIZE);
            runOnUiThread(() -> {
                if (query.equals(activeQuery)) {
                    adapter.append(page);
                    pageOffset += page.size();
                }
                loadingMore = false;
            });
        });
    }

    private void openMessage(Message message) {
        if (actionMode != null) {
            toggleSelection(message);
        } else {
            Intent intent = new Intent(this, MessageViewActivity.class);
            intent.putExtra(MessageViewActivity.EXTRA_ACCOUNT_ID, accountId);
            intent.putExtra(MessageViewActivity.EXTRA_MESSAGE_ID, message.id());
            startActivity(intent);
        }
    }

    private void onMessageLongClick(Message message) {
        if (actionMode == null) {
            actionMode = startSupportActionMode(new ActionModeCallback());
        }
        toggleSelection(message);
    }

    private void toggleSelection(Message message) {
        if (selectedMessages.contains(message)) {
            selectedMessages.remove(message);
        } else {
            selectedMessages.add(message);
        }
        adapter.notifyDataSetChanged();
        updateActionModeTitle();
        
        if (selectedMessages.isEmpty() && actionMode != null) {
            actionMode.finish();
        }
    }

    private void updateActionModeTitle() {
        if (actionMode != null) {
            actionMode.setTitle(selectedMessages.size() + " " + getString(R.string.message_list_selection_count));
        }
    }

    private void compose() {
        Intent intent = new Intent(this, ComposeActivity.class);
        intent.putExtra(ComposeActivity.EXTRA_ACCOUNT_ID, accountId);
        startActivity(intent);
    }

    private class ActionModeCallback implements androidx.appcompat.view.ActionMode.Callback {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            MenuInflater inflater = mode.getMenuInflater();
            inflater.inflate(R.menu.message_list_context, menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            int id = item.getItemId();
            if (id == R.id.action_delete) {
                deleteSelected();
                mode.finish();
                return true;
            } else if (id == R.id.action_archive) {
                archiveSelected();
                mode.finish();
                return true;
            } else if (id == R.id.action_mark_read) {
                markSelectedAsRead(true);
                mode.finish();
                return true;
            } else if (id == R.id.action_mark_unread) {
                markSelectedAsRead(false);
                mode.finish();
                return true;
            } else if (id == R.id.action_select_all) {
                selectAll();
                return true;
            } else if (id == R.id.action_star) {
                changeSelectedFlags();
                mode.finish();
                return true;
            } else if (id == R.id.action_spam) {
                moveSelected(FolderType.SPAM);
                mode.finish();
                return true;
            } else if (id == R.id.action_move) {
                chooseMoveFolder();
                return true;
            }
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            actionMode = null;
            selectedMessages.clear();
            adapter.notifyDataSetChanged();
        }
    }

    private void deleteSelected() {
        List<Message> target = new ArrayList<>(selectedMessages);
        for (Message message : target) {
            context.store().account(message.accountId()).ifPresent(account -> {
                Folder trash = context.store().listFolders(account.id()).stream()
                        .filter(folder -> folder.type() == FolderType.TRASH).findFirst().orElse(null);
                if (trash == null) context.syncEngine().queue(account, PendingAction.Type.DELETE, message.id(), "");
                else context.syncEngine().queue(account, PendingAction.Type.MOVE, message.id(), trash.id());
            });
        }
        load();
        Toast.makeText(this, target.size() + " messages queued for deletion", Toast.LENGTH_SHORT).show();
    }

    private void archiveSelected() {
        List<Message> target = new ArrayList<>(selectedMessages);
        for (Message message : target) context.store().account(message.accountId()).ifPresent(account ->
                context.store().listFolders(account.id()).stream()
                        .filter(folder -> folder.type() == FolderType.ARCHIVE).findFirst()
                        .ifPresent(folder -> context.syncEngine().queue(account, PendingAction.Type.MOVE,
                                message.id(), folder.id())));
        load();
        Toast.makeText(this, target.size() + " archive actions queued", Toast.LENGTH_SHORT).show();
    }

    private void changeSelectedFlags() {
        List<Message> target = new ArrayList<>(selectedMessages);
        for (Message message : target) context.store().account(message.accountId()).ifPresent(account ->
                context.syncEngine().queue(account, message.flagged() ? PendingAction.Type.UNFLAG
                        : PendingAction.Type.FLAG, message.id(), ""));
        load();
        Toast.makeText(this, target.size() + " star actions queued", Toast.LENGTH_SHORT).show();
    }

    private void moveSelected(FolderType type) {
        List<Message> target = new ArrayList<>(selectedMessages);
        for (Message message : target) context.store().account(message.accountId()).ifPresent(account ->
                context.store().listFolders(account.id()).stream().filter(folder -> folder.type() == type)
                        .findFirst().ifPresent(folder -> context.syncEngine().queue(account,
                                PendingAction.Type.MOVE, message.id(), folder.id())));
        load();
        Toast.makeText(this, target.size() + " move actions queued", Toast.LENGTH_SHORT).show();
    }

    private void chooseMoveFolder() {
        List<Folder> folders = context.store().listFolders(accountId).stream()
                .filter(Folder::selectable).filter(folder -> !folder.id().equals(folderId)).toList();
        String[] names = folders.stream().map(Folder::name).toArray(String[]::new);
        if (names.length == 0) return;
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Move selected messages to")
                .setItems(names, (dialog, which) -> {
                    String destination = folders.get(which).id();
                    List<Message> target = new ArrayList<>(selectedMessages);
                    for (Message message : target) context.store().account(message.accountId()).ifPresent(account ->
                            context.syncEngine().queue(account, PendingAction.Type.MOVE, message.id(), destination));
                    load();
                    if (actionMode != null) actionMode.finish();
                }).show();
    }

    private void markSelectedAsRead(boolean read) {
        List<Message> target = new ArrayList<>(selectedMessages);
        for (Message message : target) {
            context.store().account(message.accountId()).ifPresent(account -> context.syncEngine().queue(account,
                    read ? PendingAction.Type.MARK_READ : PendingAction.Type.MARK_UNREAD, message.id(), ""));
        }
        load();
        Toast.makeText(this, target.size() + " messages queued as " + (read ? "read" : "unread"), Toast.LENGTH_SHORT).show();
    }

    private void selectAll() {
        // For now, select all visible messages
        List<Message> allMessages = adapter.getMessages();
        selectedMessages.clear();
        selectedMessages.addAll(allMessages);
        adapter.notifyDataSetChanged();
        updateActionModeTitle();
    }

    /**
     * The adapter of the message list.
     */
    static final class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.Holder> {

        private final List<Message> messages = new ArrayList<>();
        private final OnMessageSelected listener;
        private final OnMessageLongClick longClickListener;
        private List<Message> selectedMessages = new ArrayList<>();

        MessageAdapter(OnMessageSelected listener, OnMessageLongClick longClickListener) {
            this.listener = listener;
            this.longClickListener = longClickListener;
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

        List<Message> getMessages() {
            return new ArrayList<>(messages);
        }

        void setSelectedMessages(List<Message> selected) {
            this.selectedMessages = selected;
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
            holder.bind(messages.get(position), selectedMessages, listener, longClickListener);
        }

        @Override
        public int getItemCount() {
            return messages.size();
        }

        /** The callback that is called when a message is opened. */
        interface OnMessageSelected {
            void onMessageSelected(Message message);
        }

        /** The callback that is called when a message is long-clicked. */
        interface OnMessageLongClick {
            void onMessageLongClick(Message message);
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

            void bind(Message message, List<Message> selected, OnMessageSelected listener,
                      OnMessageLongClick longClickListener) {
                boolean isSelected = selected.contains(message);
                sender.setText(message.from().displayLabel());
                subject.setText(message.subject());
                snippet.setText(TextTools.snippet(
                        message.snippet().isEmpty() ? message.bodyText() : message.snippet(), 120));
                date.setText(message.date() == null ? "" : message.date().toString());
                subject.setTypeface(null, message.unread()
                        ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                itemView.setActivated(isSelected);
                itemView.setBackgroundColor(isSelected 
                        ? itemView.getContext().getColor(android.R.color.holo_blue_light) 
                        : itemView.getContext().getColor(android.R.color.transparent));
                
                itemView.setOnClickListener(view -> {
                    if (listener != null) listener.onMessageSelected(message);
                });
                
                itemView.setOnLongClickListener(view -> {
                    if (longClickListener != null) {
                        longClickListener.onMessageLongClick(message);
                        return true;
                    }
                    return false;
                });
            }
        }
    }
}
