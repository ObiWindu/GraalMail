/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
    private LinearLayout ccBccToggle;
    private TextView showCcBcc;
    private ImageView ccBccArrow;
    private LinearLayout attachmentsLayout;
    private RecyclerView attachmentsList;
    private AttachmentAdapter attachmentAdapter;
    private List<Uri> attachmentUris = new ArrayList<>();

    private final ActivityResultLauncher<Intent> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        for (int i = 0; i < count; i++) {
                            Uri uri = data.getClipData().getItemAt(i).getUri();
                            if (uri != null) {
                                addAttachment(uri);
                            }
                        }
                    } else if (data.getData() != null) {
                        addAttachment(data.getData());
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_compose);
        context = AppContext.of(this);

        accountId = getIntent().getStringExtra(EXTRA_ACCOUNT_ID);
        if (accountId == null || accountId.isEmpty()) {
            accountId = defaultAccount();
        }

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.compose_title);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(view -> {
            if (hasUnsavedChanges()) {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(R.string.compose_discard)
                        .setMessage(R.string.compose_discard_confirm)
                        .setPositiveButton(R.string.action_discard, (dialog, which) -> finish())
                        .setNegativeButton(R.string.action_cancel, null)
                        .show();
            } else {
                finish();
            }
        });

        toField = findViewById(R.id.compose_to);
        ccField = findViewById(R.id.compose_cc);
        bccField = findViewById(R.id.compose_bcc);
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
            attachmentAdapter.notifyDataSetChanged();
            updateAttachmentsVisibility();
        });
        attachmentsList.setLayoutManager(new LinearLayoutManager(this));
        attachmentsList.setAdapter(attachmentAdapter);

        findViewById(R.id.compose_add_attachment).setOnClickListener(view -> pickAttachments());

        toField.setText(getIntent().getStringExtra(EXTRA_TO));
        subjectField.setText(getIntent().getStringExtra(EXTRA_SUBJECT));
        bodyField.setText(getIntent().getStringExtra(EXTRA_BODY));

        findViewById(R.id.compose_send).setOnClickListener(view -> send());
        findViewById(R.id.compose_draft).setOnClickListener(view -> saveDraft());
    }

    private String defaultAccount() {
        Optional<Account> account = context.store().listAccounts().stream().findFirst();
        return account.map(Account::id).orElse("");
    }

    private void toggleCcBcc() {
        boolean visible = ccField.getVisibility() == View.VISIBLE;
        ccField.setVisibility(visible ? View.GONE : View.VISIBLE);
        bccField.setVisibility(visible ? View.GONE : View.VISIBLE);
        ccBccArrow.setImageResource(visible ? android.R.drawable.arrow_down_float : android.R.drawable.arrow_up_float);
    }

    private void pickAttachments() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        filePickerLauncher.launch(intent);
    }

    private void addAttachment(Uri uri) {
        try {
            long size = getContentResolver().openFileDescriptor(uri, "r").getStatSize();
            if (size > MAX_ATTACHMENT_SIZE) {
                Toast.makeText(this, R.string.compose_attachment_too_large, Toast.LENGTH_LONG).show();
                return;
            }
            if (!attachmentUris.contains(uri)) {
                attachmentUris.add(uri);
                attachmentAdapter.notifyDataSetChanged();
                updateAttachmentsVisibility();
                Toast.makeText(this, getString(R.string.compose_attachment_added, getFileName(uri)), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            LOG.warn("Failed to add attachment: {}", e.getMessage());
            Toast.makeText(this, R.string.compose_attachment_picker_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private String getFileName(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            return uri.getLastPathSegment();
        } catch (Exception e) {
            return "attachment";
        }
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
                if (done) {
                    Toast.makeText(this, text, Toast.LENGTH_LONG).show();
                    finish();
                }
            });
        });
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
                .cc(AddressParser.parseList(bccText))
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

        AttachmentAdapter(List<Uri> uris, java.util.function.Consumer<Uri> onRemove) {
            this.uris = uris;
            this.onRemove = onRemove;
        }

        @Override
        public ViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View view = android.view.LayoutInflater.from(parent.getContext())
                    .inflate(android.R.layout.simple_list_item_2, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            Uri uri = uris.get(position);
            holder.name.setText(uri.getLastPathSegment());
            holder.itemView.setOnClickListener(v -> onRemove.accept(uri));
        }

        @Override
        public int getItemCount() {
            return uris.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView name;
            ViewHolder(android.view.View itemView) {
                super(itemView);
                name = itemView.findViewById(android.R.id.text1);
            }
        }
    }
}
