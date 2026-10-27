/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.graalmail.android.AppContext;
import com.graalmail.android.R;
import com.graalmail.android.util.ComposeValidator;
import com.graalmail.core.domain.Account;
import com.graalmail.core.domain.Address;
import com.graalmail.core.domain.MessageDraft;
import com.graalmail.core.error.ProviderException;
import com.graalmail.core.text.AddressParser;
import com.graalmail.core.util.Log;
import com.graalmail.provider.api.ProviderSession;
import com.graalmail.provider.api.SendResult;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.HashMap;
import java.util.Map;

/**
 * The compose screen of the Android client.
 */
public final class ComposeActivity extends AppCompatActivity {

    /** The identifier of the account the message is sent from. */
    public static final String EXTRA_ACCOUNT_ID = "accountId";
    /** The prefilled recipient. */
    public static final String EXTRA_TO = "to";
    /** The prefilled subject. */
    public static final String EXTRA_SUBJECT = "subject";
    /** The prefilled body. */
    public static final String EXTRA_BODY = "body";

    private static final Logger LOG = Log.of(ComposeActivity.class);
    private static final int MAX_ATTACHMENT_SIZE = 25 * 1024 * 1024; // 25 MB

    private AppContext context;
    private String accountId;
    private EditText toField;
    private EditText ccField;
    private EditText bccField;
    private EditText subjectField;
    private EditText bodyField;
    private View ccContainer;
    private View bccContainer;
    private LinearLayout ccBccToggle;
    private TextView showCcBcc;
    private ImageView ccBccArrow;
    private LinearLayout attachmentsLayout;
    private RecyclerView attachmentsList;
    private AttachmentAdapter attachmentAdapter;
    private List<Uri> attachmentUris = new ArrayList<>();
    private final Map<Uri, Long> attachmentSizes = new HashMap<>();
    private static final String STATE_ATTACHMENTS = "compose.attachments";
    private static final String STATE_ATTACHMENT_SIZES = "compose.attachmentSizes";
    private static final String STATE_ACCOUNT = "compose.account";
    private boolean sendInProgress;

    private final ActivityResultLauncher<Intent> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        for (int i = 0; i < count; i++) {
                            Uri uri = data.getClipData().getItemAt(i).getUri();
                            if (uri != null) {
                                persistReadPermission(uri, data.getFlags());
                                addAttachment(uri);
                            }
                        }
                    } else if (data.getData() != null) {
                        persistReadPermission(data.getData(), data.getFlags());
                        addAttachment(data.getData());
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_compose);
        EdgeToEdge.apply(this);
        context = AppContext.of(this);
        if (savedInstanceState != null) {
            ArrayList<Uri> restored = savedInstanceState.getParcelableArrayList(STATE_ATTACHMENTS);
            if (restored != null) attachmentUris.addAll(restored);
            long[] sizes = savedInstanceState.getLongArray(STATE_ATTACHMENT_SIZES);
            if (sizes != null) {
                for (int i = 0; i < Math.min(sizes.length, attachmentUris.size()); i++) {
                    attachmentSizes.put(attachmentUris.get(i), sizes[i]);
                }
            }
        }

        accountId = savedInstanceState == null ? getIntent().getStringExtra(EXTRA_ACCOUNT_ID)
                : savedInstanceState.getString(STATE_ACCOUNT, getIntent().getStringExtra(EXTRA_ACCOUNT_ID));
        if (accountId == null || accountId.isEmpty()) {
            accountId = defaultAccount();
        }
        List<Account> accounts = context.store().listAccounts();
        Spinner accountPicker = findViewById(R.id.compose_account);
        ArrayList<String> accountNames = new ArrayList<>();
        int selectedAccount = 0;
        for (int i = 0; i < accounts.size(); i++) {
            Account account = accounts.get(i);
            accountNames.add(account.displayName().isBlank() || account.displayName().equals(account.email())
                    ? account.email() : account.displayName() + " <" + account.email() + ">");
            if (account.id().equals(accountId)) selectedAccount = i;
        }
        ArrayAdapter<String> accountAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, accountNames);
        accountAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        accountPicker.setAdapter(accountAdapter);
        accountPicker.setSelection(selectedAccount, false);
        accountPicker.setVisibility(accounts.size() > 1 ? View.VISIBLE : View.GONE);
        TextView accountIdentity = findViewById(R.id.compose_account_identity);
        TextView accountMissing = findViewById(R.id.compose_account_missing);
        if (accounts.size() == 1) {
            accountIdentity.setText(accountNames.get(0));
            accountIdentity.setVisibility(View.VISIBLE);
        } else if (accounts.isEmpty()) {
            accountMissing.setVisibility(View.VISIBLE);
        }
        accountPicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < accounts.size()) accountId = accounts.get(position).id();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.compose_title);
        setSupportActionBar(toolbar);
        toolbar.inflateMenu(R.menu.menu_compose);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_send_message) {
                send();
                return true;
            }
            return false;
        });
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(view -> closeCompose());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { closeCompose(); }
        });

        toField = findViewById(R.id.compose_to);
        ccField = findViewById(R.id.compose_cc);
        bccField = findViewById(R.id.compose_bcc);
        ccContainer = findViewById(R.id.compose_cc_container);
        bccContainer = findViewById(R.id.compose_bcc_container);
        subjectField = findViewById(R.id.compose_subject);
        bodyField = findViewById(R.id.compose_body);

        ccBccToggle = findViewById(R.id.compose_cc_bcc_toggle);
        showCcBcc = findViewById(R.id.compose_show_cc_bcc);
        ccBccArrow = findViewById(R.id.compose_cc_bcc_arrow);
        ccBccToggle.setOnClickListener(view -> toggleCcBcc());

        attachmentsLayout = findViewById(R.id.compose_attachments);
        attachmentsList = findViewById(R.id.compose_attachments_list);
        attachmentAdapter = new AttachmentAdapter(attachmentUris, uri -> {
            attachmentUris.remove(uri);
            attachmentSizes.remove(uri);
            releasePersistedPermission(uri);
            attachmentAdapter.notifyDataSetChanged();
            updateAttachmentsVisibility();
        }, attachmentSizes);
        attachmentsList.setLayoutManager(new LinearLayoutManager(this));
        attachmentsList.setAdapter(attachmentAdapter);
        updateAttachmentsVisibility();

        findViewById(R.id.compose_add_attachment).setOnClickListener(view -> pickAttachments());

        toField.setText(getIntent().getStringExtra(EXTRA_TO));
        subjectField.setText(getIntent().getStringExtra(EXTRA_SUBJECT));
        bodyField.setText(getIntent().getStringExtra(EXTRA_BODY));

        findViewById(R.id.compose_draft).setOnClickListener(view -> saveDraft());
    }

    private void closeCompose() {
        if (!hasUnsavedChanges()) {
            finish();
            return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.compose_discard)
                .setMessage(R.string.compose_discard_confirm)
                .setPositiveButton(R.string.action_discard, (dialog, which) -> finish())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putParcelableArrayList(STATE_ATTACHMENTS, new ArrayList<>(attachmentUris));
        long[] sizes = new long[attachmentUris.size()];
        for (int i = 0; i < attachmentUris.size(); i++) {
            sizes[i] = attachmentSizes.getOrDefault(attachmentUris.get(i), -1L);
        }
        outState.putLongArray(STATE_ATTACHMENT_SIZES, sizes);
        outState.putString(STATE_ACCOUNT, accountId);
        super.onSaveInstanceState(outState);
    }

    private String defaultAccount() {
        Optional<Account> account = context.store().listAccounts().stream().findFirst();
        return account.map(Account::id).orElse("");
    }

    private void toggleCcBcc() {
        boolean visible = ccContainer.getVisibility() == View.VISIBLE;
        ccContainer.setVisibility(visible ? View.GONE : View.VISIBLE);
        bccContainer.setVisibility(visible ? View.GONE : View.VISIBLE);
        showCcBcc.setText(visible ? R.string.compose_show_cc_bcc : R.string.compose_hide_cc_bcc);
        androidx.core.view.ViewCompat.setStateDescription(ccBccToggle,
                getString(visible ? R.string.compose_show_cc_bcc : R.string.compose_hide_cc_bcc));
        ccBccArrow.setImageResource(visible ? android.R.drawable.arrow_down_float : android.R.drawable.arrow_up_float);
    }

    private void pickAttachments() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        filePickerLauncher.launch(intent);
    }

    private void addAttachment(Uri uri) {
        try (ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(uri, "r")) {
            if (descriptor == null) throw new java.io.IOException("The file could not be opened");
            long size = descriptor.getStatSize();
            if (size > MAX_ATTACHMENT_SIZE) {
                releasePersistedPermission(uri);
                Toast.makeText(this, R.string.compose_attachment_too_large, Toast.LENGTH_LONG).show();
                return;
            }
            if (!attachmentUris.contains(uri)) {
                attachmentUris.add(uri);
                attachmentSizes.put(uri, size);
                attachmentAdapter.notifyDataSetChanged();
                updateAttachmentsVisibility();
                Toast.makeText(this, getString(R.string.compose_attachment_added, getFileName(uri)), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            releasePersistedPermission(uri);
            LOG.warn("Failed to add attachment: {}", e.getMessage());
            Toast.makeText(this, R.string.compose_attachment_picker_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void persistReadPermission(Uri uri, int resultFlags) {
        int read = resultFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (read == 0) return;
        try {
            getContentResolver().takePersistableUriPermission(uri, read);
        } catch (SecurityException ignored) {
            // Some document providers grant access only for the current activity session.
        }
    }

    private void releasePersistedPermission(Uri uri) {
        try {
            getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // The picker may have returned a temporary, non-persistable grant.
        }
    }

    private String getFileName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (column >= 0) return cursor.getString(column);
            }
        } catch (RuntimeException ignored) {
            // Fall back to the last URI segment when the document provider has no display name.
        }
        String name = uri.getLastPathSegment();
        return name == null || name.isBlank() ? "attachment" : name;
    }

    private void updateAttachmentsVisibility() {
        boolean visible = !attachmentUris.isEmpty();
        attachmentsLayout.setVisibility(visible ? View.VISIBLE : View.GONE);
        findViewById(R.id.compose_add_attachment).setVisibility(visible ? View.GONE : View.VISIBLE);
    }

    private boolean hasUnsavedChanges() {
        return !toField.getText().toString().trim().isEmpty()
                || !ccField.getText().toString().trim().isEmpty()
                || !bccField.getText().toString().trim().isEmpty()
                || !subjectField.getText().toString().trim().isEmpty()
                || !bodyField.getText().toString().trim().isEmpty()
                || !attachmentUris.isEmpty();
    }

    private void send() {
        if (sendInProgress) return;
        String toText = toField.getText().toString();
        String ccText = ccField.getText().toString();
        String bccText = bccField.getText().toString();
        String bodyText = bodyField.getText().toString();

        ComposeValidator.Result validation = ComposeValidator.validate(toText, ccText, bccText, bodyText);
        if (!validation.isValid()) {
            ComposeValidator.Problem problem = validation.problems().get(0);
            Toast.makeText(this, problem.message(), Toast.LENGTH_LONG).show();
            return;
        }
        TextView status = findViewById(R.id.compose_status);
        sendInProgress = true;
        toolbarSendEnabled(false);
        status.setText(R.string.compose_sending);
        MessageDraft draft = MessageDraft.builder()
                .accountId(accountId)
                .to(AddressParser.parseList(toText))
                .cc(AddressParser.parseList(ccText))
                .bcc(AddressParser.parseList(bccText))
                .subject(subjectField.getText().toString())
                .bodyText(bodyText)
                .build();
        context.io().execute(() -> {
            String message;
            boolean finished = false;
            try (ProviderSession session = context.sessions().open(account())) {
                SendResult result = session.submission().send(draft);
                if (result.accepted()) {
                    message = getString(R.string.compose_sent);
                    finished = true;
                } else {
                    message = getString(R.string.compose_ambiguous);
                }
            } catch (ProviderException e) {
                message = getString(R.string.compose_failed, e.userMessage());
            } catch (RuntimeException e) {
                LOG.warn("The message could not be sent: {}", e.getMessage());
                message = getString(R.string.compose_failed, e.getMessage());
            }
            String text = message;
            boolean done = finished;
            runOnUiThread(() -> {
                status.setText(text);
                sendInProgress = false;
                if (done) {
                    Toast.makeText(this, text, Toast.LENGTH_LONG).show();
                    finish();
                } else {
                    toolbarSendEnabled(true);
                }
            });
        });
    }

    private void toolbarSendEnabled(boolean enabled) {
        Toolbar toolbar = findViewById(R.id.toolbar);
        android.view.MenuItem send = toolbar.getMenu().findItem(R.id.action_send_message);
        if (send != null) send.setEnabled(enabled);
    }

    private void saveDraft() {
        String toText = toField.getText().toString();
        String ccText = ccField.getText().toString();
        String bccText = bccField.getText().toString();
        String bodyText = bodyField.getText().toString();

        MessageDraft draft = MessageDraft.builder()
                .accountId(accountId)
                .to(AddressParser.parseList(toText))
                .cc(AddressParser.parseList(ccText))
                .bcc(AddressParser.parseList(bccText))
                .subject(subjectField.getText().toString())
                .bodyText(bodyText)
                .build();
        context.io().execute(() -> {
            String message;
            try (ProviderSession session = context.sessions().open(account())) {
                session.submission().saveDraft(draft);
                message = getString(R.string.compose_draft_saved);
            } catch (RuntimeException e) {
                message = getString(R.string.compose_failed, e.getMessage());
            }
            String text = message;
            runOnUiThread(() -> {
                Toast.makeText(this, text, Toast.LENGTH_LONG).show();
                if (text.equals(getString(R.string.compose_draft_saved))) {
                    finish();
                }
            });
        });
    }

    private Account account() {
        return context.store().account(accountId)
                .orElseThrow(() -> new IllegalStateException("The account is missing"));
    }

    private static class AttachmentAdapter extends RecyclerView.Adapter<AttachmentAdapter.ViewHolder> {
        private final List<Uri> uris;
        private final java.util.function.Consumer<Uri> onRemove;
        private final Map<Uri, Long> sizes;

        AttachmentAdapter(List<Uri> uris, java.util.function.Consumer<Uri> onRemove,
                          Map<Uri, Long> sizes) {
            this.uris = uris;
            this.onRemove = onRemove;
            this.sizes = sizes;
        }

        @Override
        public ViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View view = android.view.LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_compose_attachment, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            Uri uri = uris.get(position);
            long size = sizes.getOrDefault(uri, -1L);
            String sizeLabel = size < 0 ? "" : android.text.format.Formatter.formatShortFileSize(
                    holder.itemView.getContext(), size);
            holder.name.setText(holder.itemView.getResources().getString(R.string.compose_attachment_size,
                    attachmentName(holder.itemView.getContext(), uri), sizeLabel));
            holder.remove.setOnClickListener(v -> onRemove.accept(uri));
        }

        @Override
        public int getItemCount() {
            return uris.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView name;
            View remove;
            ViewHolder(android.view.View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.compose_attachment_name);
                remove = itemView.findViewById(R.id.compose_attachment_remove);
            }
        }
    }

    private static String attachmentName(android.content.Context context, Uri uri) {
        try (android.database.Cursor cursor = context.getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (column >= 0) return cursor.getString(column);
            }
        } catch (RuntimeException ignored) {
            // Fall back to a URI-derived label for document providers without metadata.
        }
        String name = uri.getLastPathSegment();
        return name == null || name.isBlank() ? "attachment" : name;
    }
}
