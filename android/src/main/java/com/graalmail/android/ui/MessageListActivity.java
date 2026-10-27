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
import androidx.drawerlayout.widget.DrawerLayout;
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
    private MailboxNavigation mailboxNavigation;
    private String accountId;
    private String folderId;
    private MessageAdapter adapter;
    private TextView emptyView;
    private TextView pagingStatus;
    private androidx.appcompat.view.ActionMode actionMode;
    private final List<Message> selectedMessages = new ArrayList<>();
    private int pageOffset;
    private boolean loadingMore;
    private boolean canLoadMore = true;
    private String activeQuery = "";
    private int requestGeneration;
    private static final String STATE_QUERY = "message_list.query";
    private static final String STATE_PAGE_OFFSET = "message_list.pageOffset";
    private static final String STATE_UNREAD = "message_list.unread";
    private static final String STATE_STARRED = "message_list.starred";
    private static final String STATE_ATTACHMENTS = "message_list.attachments";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_message_list);
        EdgeToEdge.apply(this);
        context = AppContext.of(this);

        accountId = getIntent().getStringExtra(EXTRA_ACCOUNT_ID);
        folderId = getIntent().getStringExtra(EXTRA_FOLDER_ID);
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
        String folderName = getIntent().getStringExtra(EXTRA_FOLDER_NAME);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(folderName == null ? getString(R.string.message_list_title) : folderName);
        setSupportActionBar(toolbar);
        toolbar.setNavigationIcon(R.drawable.ic_menu);
        toolbar.setNavigationContentDescription(R.string.mailbox_open_navigation);
        DrawerLayout mailboxDrawer = findViewById(R.id.mailbox_drawer);
        mailboxNavigation = new MailboxNavigation(this, mailboxDrawer, false, accountId, folderId);
        toolbar.inflateMenu(R.menu.menu_message_list);
        toolbar.setNavigationOnClickListener(view -> {
            if (actionMode != null) actionMode.finish();
            else mailboxNavigation.open();
        });

        MenuItem searchItem = toolbar.getMenu().findItem(R.id.action_search_messages);
        SearchView searchView = (SearchView) searchItem.getActionView();
        searchView.setQueryHint(getString(R.string.message_list_search_hint));
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
                    loadFiltered();
                }
                return false;
            }
        });
        searchView.setOnCloseListener(() -> {
            load();
            return false;
        });
        if (!activeQuery.isBlank()) {
            searchItem.expandActionView();
            searchView.setQuery(activeQuery, false);
        }

        RecyclerView list = findViewById(R.id.message_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MessageAdapter(this::openMessage, this::onMessageLongClick, this::toggleStar);
        list.setAdapter(adapter);
        new MessageSwipeHelper(adapter::messageAt, new MessageSwipeHelper.SwipeActions() {
            @Override public void onSwipeRight(Message message) {
                toggleRead(message);
            }

            @Override public void onSwipeLeft(Message message) {
                int position = adapter.getMessages().indexOf(message);
                queueDeletion(java.util.List.of(message));
                if (position >= 0) adapter.notifyItemChanged(position);
            }

            @Override public boolean isSwipeEnabled() {
                return actionMode == null;
            }
        }).attach(list);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                var layout = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (dy > 0 && canLoadMore && !loadingMore && layout != null
                        && layout.findLastVisibleItemPosition() >= adapter.getItemCount() - 8) loadMore();
            }
        });
        emptyView = findViewById(R.id.message_empty);
        pagingStatus = findViewById(R.id.message_paging_status);

        android.widget.CompoundButton.OnCheckedChangeListener filterListener = (button, checked) -> {
            pageOffset = 0;
            loadFiltered();
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
        com.graalmail.android.work.AndroidPolyMail.refresh(this, context.store());
        observePolyMail();
    }

    private com.graalmail.p2p.PolyMailNode.NodeListener polyMailListener;

    private void observePolyMail() {
        com.graalmail.p2p.PolyMailNode node =
                com.graalmail.android.work.AndroidPolyMail.node();
        if (node == null || polyMailListener != null) {
            return;
        }
        polyMailListener = new com.graalmail.p2p.PolyMailNode.NodeListener() {
            @Override
            public void onStatus(com.graalmail.p2p.transport.NetworkStatus status) {
                runOnUiThread(() -> pagingStatus.setText(getString(
                        R.string.message_list_polymail_status, status.toString())));
            }

            @Override
            public void onScan(com.graalmail.p2p.delivery.ScanReport report) {
                runOnUiThread(() -> {
                    if (report.delivered() > 0) {
                        loadFiltered();
                        Toast.makeText(MessageListActivity.this,
                                getResources().getQuantityString(
                                        R.plurals.message_list_polymail_new,
                                        report.delivered(), report.delivered()),
                                Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override
            public void onError(String context, Throwable err) {
                runOnUiThread(() -> pagingStatus.setText(getString(
                        R.string.message_list_polymail_error,
                        err.getMessage() == null ? context : err.getMessage())));
            }
        };
        node.addListener(polyMailListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadFiltered();
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
        pageOffset = 0;
        activeQuery = "";
        loadFiltered();
    }

    private boolean hasFilters() {
        return ((com.google.android.material.chip.Chip) findViewById(R.id.filter_unread)).isChecked()
                || ((com.google.android.material.chip.Chip) findViewById(R.id.filter_starred)).isChecked()
                || ((com.google.android.material.chip.Chip) findViewById(R.id.filter_attachments)).isChecked();
    }

    private SearchQuery.Builder queryBuilder(int limit, int offset) {
        return SearchQuery.builder().text(activeQuery).accountIds(List.of(accountId))
                .folderIds(folderId == null ? List.of() : List.of(folderId))
                .unreadOnly(((com.google.android.material.chip.Chip) findViewById(R.id.filter_unread)).isChecked())
                .flaggedOnly(((com.google.android.material.chip.Chip) findViewById(R.id.filter_starred)).isChecked())
                .withAttachmentsOnly(((com.google.android.material.chip.Chip) findViewById(R.id.filter_attachments)).isChecked())
                .limit(limit).offset(offset);
    }

    private void loadFiltered() {
        loadingMore = false;
        canLoadMore = true;
        int generation = ++requestGeneration;
        int limit = Math.max(pageOffset, PAGE_SIZE);
        boolean filtered = hasFilters();
        boolean useSearch = !activeQuery.isBlank() || filtered;
        SearchQuery querySpec = useSearch ? queryBuilder(limit, 0).build() : null;
        findViewById(R.id.filter_clear).setVisibility(filtered ? View.VISIBLE : View.GONE);
        pagingStatus.setVisibility(View.GONE);
        View loading = findViewById(R.id.message_loading);
        View offlineNotice = findViewById(R.id.message_offline_notice);
        if (loading != null) {
            loading.setVisibility(View.VISIBLE);
        }
        emptyView.setVisibility(View.GONE);
        context.io().execute(() -> {
            List<Message> messages;
            String failure = null;
            try {
                messages = !useSearch
                        ? new ArrayList<>(context.store().listMessages(accountId, folderId, limit, 0).messages())
                        : context.store().search(querySpec, limit);
            } catch (RuntimeException e) {
                messages = List.of();
                failure = e.getMessage();
            }
            List<Message> loaded = messages;
            String loadFailure = failure;
            runOnUiThread(() -> {
                if (generation != requestGeneration) return;
                if (loading != null) {
                    loading.setVisibility(View.GONE);
                }
                if (loadFailure != null) {
                    emptyView.setVisibility(View.VISIBLE);
                    emptyView.setText(getString(R.string.message_list_load_failed));
                    pagingStatus.setText(getString(R.string.message_list_load_failed));
                    pagingStatus.setOnClickListener(view -> loadFiltered());
                    pagingStatus.setVisibility(View.VISIBLE);
                    if (offlineNotice != null) {
                        offlineNotice.setVisibility(View.GONE);
                    }
                    return;
                }
                adapter.submit(loaded);
                pageOffset = loaded.size();
                canLoadMore = loaded.size() >= limit;
                emptyView.setVisibility(loaded.isEmpty() ? View.VISIBLE : View.GONE);
                emptyView.setText(activeQuery.isBlank()
                        ? (filtered ? R.string.message_list_empty_filters : R.string.message_list_empty)
                        : R.string.message_list_empty_search);
                if (offlineNotice != null) {
                    boolean offline = !isOnline();
                    offlineNotice.setVisibility(offline && !loaded.isEmpty() ? View.VISIBLE : View.GONE);
                }
            });
        });
    }

    private boolean isOnline() {
        android.net.ConnectivityManager manager =
                (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (manager == null) {
            return true;
        }
        android.net.Network network = manager.getActiveNetwork();
        if (network == null) {
            return false;
        }
        android.net.NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null && (capabilities.hasTransport(
                android.net.NetworkCapabilities.TRANSPORT_WIFI)
                || capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
                || capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    private void search(String query) {
        activeQuery = query == null ? "" : query;
        pageOffset = 0;
        loadFiltered();
    }

    private void loadMore() {
        if (!canLoadMore) return;
        loadingMore = true;
        pagingStatus.setText(R.string.message_list_loading_more);
        pagingStatus.setOnClickListener(null);
        pagingStatus.setVisibility(View.VISIBLE);
        int offset = pageOffset;
        String query = activeQuery;
        int generation = requestGeneration;
        boolean useSearch = !query.isBlank() || hasFilters();
        SearchQuery querySpec = useSearch ? queryBuilder(PAGE_SIZE, offset).build() : null;
        context.io().execute(() -> {
            List<Message> page;
            String failure = null;
            try {
                page = !useSearch
                        ? context.store().listMessages(accountId, folderId, PAGE_SIZE, offset).messages()
                        : context.store().search(querySpec, PAGE_SIZE);
            } catch (RuntimeException e) {
                page = List.of();
                failure = e.getMessage();
            }
            List<Message> loadedPage = page;
            String loadFailure = failure;
            runOnUiThread(() -> {
                if (generation == requestGeneration && query.equals(activeQuery)) {
                    if (loadFailure != null) {
                        pagingStatus.setText(R.string.message_list_load_failed);
                        pagingStatus.setOnClickListener(view -> loadMore());
                        pagingStatus.setVisibility(View.VISIBLE);
                    } else {
                        adapter.append(loadedPage);
                        pageOffset += loadedPage.size();
                        canLoadMore = loadedPage.size() >= PAGE_SIZE;
                        pagingStatus.setVisibility(View.GONE);
                    }
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
            adapter.setSelectionMode(true);
        }
        toggleSelection(message);
    }

    private void toggleSelection(Message message) {
        if (selectedMessages.contains(message)) {
            selectedMessages.remove(message);
        } else {
            selectedMessages.add(message);
        }
        adapter.setSelectedMessages(new ArrayList<>(selectedMessages));
        updateActionModeTitle();
        
        if (selectedMessages.isEmpty() && actionMode != null) {
            actionMode.finish();
        }
    }

    private void updateActionModeTitle() {
        if (actionMode != null) {
            actionMode.setTitle(getString(R.string.message_list_selection_count, selectedMessages.size()));
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
            adapter.setSelectedMessages(List.of());
            adapter.setSelectionMode(false);
        }
    }

    private void deleteSelected() {
        List<Message> target = new ArrayList<>(selectedMessages);
        int withoutTrash = 0;
        for (Message message : target) {
            var account = context.store().account(message.accountId());
            if (account.isPresent() && context.store().listFolders(account.get().id()).stream()
                    .noneMatch(folder -> folder.type() == FolderType.TRASH)) withoutTrash++;
        }
        if (withoutTrash > 0) {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.action_delete)
                    .setMessage(getString(R.string.message_list_delete_permanent_confirm, withoutTrash))
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.action_delete, (dialog, which) -> queueDeletion(target))
                    .show();
        } else {
            queueDeletion(target);
        }
    }

    private void queueDeletion(List<Message> target) {
        int queued = 0;
        for (Message message : target) {
            var account = context.store().account(message.accountId());
            if (account.isEmpty()) continue;
            Folder trash = context.store().listFolders(account.get().id()).stream()
                    .filter(folder -> folder.type() == FolderType.TRASH).findFirst().orElse(null);
            if (trash == null) context.syncEngine().queue(account.get(), PendingAction.Type.DELETE, message.id(), "");
            else context.syncEngine().queue(account.get(), PendingAction.Type.MOVE, message.id(), trash.id());
            queued++;
        }
        loadFiltered();
        Toast.makeText(this, getString(R.string.message_list_delete_queued, queued, target.size()), Toast.LENGTH_SHORT).show();
    }

    private void archiveSelected() {
        List<Message> target = new ArrayList<>(selectedMessages);
        int queued = 0;
        for (Message message : target) {
            var account = context.store().account(message.accountId());
            if (account.isEmpty()) continue;
            Folder archive = context.store().listFolders(account.get().id()).stream()
                    .filter(folder -> folder.type() == FolderType.ARCHIVE).findFirst().orElse(null);
            if (archive == null) continue;
            context.syncEngine().queue(account.get(), PendingAction.Type.MOVE, message.id(), archive.id());
            queued++;
        }
        loadFiltered();
        Toast.makeText(this, getString(R.string.message_list_archive_queued, queued, target.size()), Toast.LENGTH_SHORT).show();
    }

    private void changeSelectedFlags() {
        List<Message> target = new ArrayList<>(selectedMessages);
        int queued = 0;
        for (Message message : target) {
            var account = context.store().account(message.accountId());
            if (account.isEmpty()) continue;
            context.syncEngine().queue(account.get(), message.flagged() ? PendingAction.Type.UNFLAG
                    : PendingAction.Type.FLAG, message.id(), "");
            queued++;
        }
        loadFiltered();
        Toast.makeText(this, getString(R.string.message_list_star_queued, queued, target.size()), Toast.LENGTH_SHORT).show();
    }

    private void moveSelected(FolderType type) {
        List<Message> target = new ArrayList<>(selectedMessages);
        int queued = 0;
        for (Message message : target) {
            var account = context.store().account(message.accountId());
            if (account.isEmpty()) continue;
            Folder destination = context.store().listFolders(account.get().id()).stream()
                    .filter(folder -> folder.type() == type).findFirst().orElse(null);
            if (destination == null) continue;
            context.syncEngine().queue(account.get(), PendingAction.Type.MOVE, message.id(), destination.id());
            queued++;
        }
        loadFiltered();
        Toast.makeText(this, getString(R.string.message_list_move_queued, queued, target.size()), Toast.LENGTH_SHORT).show();
    }

    private void chooseMoveFolder() {
        List<Folder> folders = context.store().listFolders(accountId).stream()
                .filter(Folder::selectable).filter(folder -> !folder.id().equals(folderId)).toList();
        String[] names = folders.stream().map(Folder::name).toArray(String[]::new);
        if (names.length == 0) return;
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.message_list_move_to)
                .setItems(names, (dialog, which) -> {
                    String destination = folders.get(which).id();
                    List<Message> target = new ArrayList<>(selectedMessages);
                    int queued = 0;
                    for (Message message : target) {
                        var account = context.store().account(message.accountId());
                        if (account.isEmpty()) continue;
                        context.syncEngine().queue(account.get(), PendingAction.Type.MOVE, message.id(), destination);
                        queued++;
                    }
                    loadFiltered();
                    Toast.makeText(this, getString(R.string.message_list_move_queued, queued, target.size()), Toast.LENGTH_SHORT).show();
                    if (actionMode != null) actionMode.finish();
                }).show();
    }

    private void toggleStar(Message message) {
        boolean flagged = !message.flagged();
        var account = context.store().account(message.accountId());
        if (account.isEmpty()) return;
        context.syncEngine().queue(account.get(),
                flagged ? PendingAction.Type.FLAG : PendingAction.Type.UNFLAG, message.id(), "");
        Message updated = message.toBuilder().flagged(flagged).build();
        context.store().saveMessage(updated);
        adapter.updateMessage(updated);
    }

    private void toggleRead(Message message) {
        boolean read = message.unread();
        var account = context.store().account(message.accountId());
        if (account.isEmpty()) return;
        context.syncEngine().queue(account.get(),
                read ? PendingAction.Type.MARK_READ : PendingAction.Type.MARK_UNREAD, message.id(), "");
        Message updated = message.toBuilder().unread(!read).build();
        context.store().saveMessage(updated);
        adapter.updateMessage(updated);
        Toast.makeText(this, read ? R.string.action_mark_read : R.string.action_mark_unread,
                Toast.LENGTH_SHORT).show();
    }

    private void markSelectedAsRead(boolean read) {        List<Message> target = new ArrayList<>(selectedMessages);
        int queued = 0;
        for (Message message : target) {
            var account = context.store().account(message.accountId());
            if (account.isEmpty()) continue;
            context.syncEngine().queue(account.get(), read ? PendingAction.Type.MARK_READ
                    : PendingAction.Type.MARK_UNREAD, message.id(), "");
            queued++;
        }
        loadFiltered();
        Toast.makeText(this, getString(R.string.message_list_read_queued, queued, target.size()), Toast.LENGTH_SHORT).show();
    }

    private void selectAll() {
        // Select every message currently loaded from the local cache.
        List<Message> allMessages = adapter.getMessages();
        selectedMessages.clear();
        selectedMessages.addAll(allMessages);
        adapter.setSelectedMessages(new ArrayList<>(selectedMessages));
        updateActionModeTitle();
    }

    /**
     * The adapter of the message list.
     */
    static final class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.Holder> {

        private final List<Message> messages = new ArrayList<>();
        private final OnMessageSelected listener;
        private final OnMessageLongClick longClickListener;
        private final OnStarToggled starListener;
        private List<Message> selectedMessages = new ArrayList<>();
        private boolean selectionMode;

        MessageAdapter(OnMessageSelected listener, OnMessageLongClick longClickListener,
                       OnStarToggled starListener) {
            this.listener = listener;
            this.longClickListener = longClickListener;
            this.starListener = starListener;
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

        Message messageAt(int position) {
            return position >= 0 && position < messages.size() ? messages.get(position) : null;
        }

        void updateMessage(Message updated) {
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i).id().equals(updated.id())) {
                    messages.set(i, updated);
                    notifyItemChanged(i);
                    return;
                }
            }
        }

        void setSelectedMessages(List<Message> selected) {
            this.selectedMessages = selected;
            notifyDataSetChanged();
        }

        void setSelectionMode(boolean enabled) {
            if (selectionMode != enabled) {
                selectionMode = enabled;
                notifyDataSetChanged();
            }
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
            holder.bind(messages.get(position), selectedMessages, selectionMode,
                    listener, longClickListener, starListener);
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

        /** The callback that is called when the star of a message is tapped. */
        interface OnStarToggled {
            void onStarToggled(Message message);
        }

        /** One row of the list. */
        static final class Holder extends RecyclerView.ViewHolder {
            private final TextView avatar;
            private final android.widget.CheckBox checkbox;
            private final TextView sender;
            private final TextView subject;
            private final TextView snippet;
            private final TextView date;
            private final android.widget.ImageButton star;
            private final View attachmentIcon;

            Holder(View view) {
                super(view);
                avatar = view.findViewById(R.id.message_avatar);
                checkbox = view.findViewById(R.id.message_checkbox);
                sender = view.findViewById(R.id.message_sender);
                subject = view.findViewById(R.id.message_subject);
                snippet = view.findViewById(R.id.message_snippet);
                date = view.findViewById(R.id.message_date);
                star = view.findViewById(R.id.message_star);
                attachmentIcon = view.findViewById(R.id.message_attachment_icon);
            }

            void bind(Message message, List<Message> selected, boolean selectionMode,
                      OnMessageSelected listener, OnMessageLongClick longClickListener,
                      OnStarToggled starListener) {
                boolean isSelected = selected.contains(message);
                String senderLabel = message.from().displayLabel();
                sender.setText(senderLabel);
                subject.setText(message.subject());
                snippet.setText(TextTools.snippet(
                        message.snippet().isEmpty() ? message.bodyText() : message.snippet(), 120));
                date.setText(UiDateFormatter.shortDateTime(message.date()));
                sender.setTypeface(null, message.unread()
                        ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                subject.setTypeface(null, message.unread()
                        ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                attachmentIcon.setVisibility(message.attachments().isEmpty() ? View.GONE : View.VISIBLE);

                if (selectionMode) {
                    avatar.setVisibility(View.INVISIBLE);
                    checkbox.setVisibility(View.VISIBLE);
                    checkbox.setChecked(isSelected);
                } else {
                    avatar.setVisibility(View.VISIBLE);
                    checkbox.setVisibility(View.GONE);
                    avatar.setText(AvatarColors.initials(
                            message.from().name(), message.from().email()));
                    avatar.getBackground().mutate().setTint(AvatarColors.colorFor(
                            message.from().name(), message.from().email()));
                    avatar.setContentDescription(senderLabel);
                }

                star.setImageResource(message.flagged()
                        ? R.drawable.ic_star_filled : R.drawable.ic_star_outline);
                star.setImageTintList(android.content.res.ColorStateList.valueOf(
                        message.flagged() ? androidx.core.content.ContextCompat.getColor(
                                        star.getContext(), R.color.star_active)
                                : com.google.android.material.color.MaterialColors.getColor(star,
                                        com.google.android.material.R.attr.colorOnSurfaceVariant)));
                star.setContentDescription(star.getResources().getString(
                        message.flagged() ? R.string.action_unstar : R.string.action_star));
                star.setOnClickListener(view -> {
                    if (starListener != null) starListener.onStarToggled(message);
                });

                itemView.setActivated(isSelected);
                itemView.setSelected(isSelected);
                itemView.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                        com.google.android.material.color.MaterialColors.getColor(itemView,
                                isSelected ? com.google.android.material.R.attr.colorPrimaryContainer
                                        : com.google.android.material.R.attr.colorSurface)));
                StringBuilder summary = new StringBuilder(senderLabel)
                        .append(". ").append(message.subject() == null ? "" : message.subject()).append(". ")
                        .append(TextTools.snippet(message.snippet().isEmpty()
                                ? message.bodyText() : message.snippet(), 120));
                if (message.date() != null) summary.append(". ").append(UiDateFormatter.shortDateTime(message.date()));
                if (message.unread()) summary.append(". ").append(itemView.getResources().getString(R.string.message_list_unread_indicator));
                if (message.flagged()) summary.append(". ").append(itemView.getResources().getString(R.string.message_list_flagged_indicator));
                if (!message.attachments().isEmpty()) summary.append(". ").append(itemView.getResources().getString(R.string.message_list_attachment_indicator));
                if (isSelected) summary.append(". ").append(itemView.getResources().getString(
                        R.string.message_list_selection_count, selected.size()));
                itemView.setContentDescription(summary.toString());

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
