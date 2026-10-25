/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.ui;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.text.style.ImageSpan;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.graalmail.android.AppContext;
import com.graalmail.android.R;
import com.graalmail.core.domain.Account;
import com.graalmail.core.domain.Attachment;
import com.graalmail.core.domain.Address;
import com.graalmail.core.domain.Message;
import com.graalmail.core.domain.Folder;
import com.graalmail.core.domain.FolderType;
import com.graalmail.storage.PendingAction;
import com.graalmail.core.text.HtmlSanitizer;
import com.graalmail.provider.api.ProviderSession;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.ZoneId;
import java.util.Locale;
import java.util.List;
import java.util.Optional;

/**
 * Shows a single message.
 *
 * <p>The body of the message is sanitised before it is rendered, so that a
 * malicious message cannot run code in the application.</p>
 */
public final class MessageViewActivity extends AppCompatActivity {

    /** The identifier of the account the message belongs to. */
    public static final String EXTRA_ACCOUNT_ID = "accountId";
    /** The identifier of the shown message. */
    public static final String EXTRA_MESSAGE_ID = "messageId";

    private AppContext context;
    private Message message;
    private String accountId;

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.message_view_actions, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_star) {
            queueFlag(!message.flagged());
            return true;
        }
        if (id == R.id.action_mark_unread) {
            queue(PendingAction.Type.MARK_UNREAD, "");
            finish();
            return true;
        }
        if (id == R.id.action_archive || id == R.id.action_spam || id == R.id.action_delete) {
            FolderType type = id == R.id.action_archive ? FolderType.ARCHIVE
                    : id == R.id.action_spam ? FolderType.SPAM : FolderType.TRASH;
            moveTo(type, id == R.id.action_delete);
            return true;
        }
        if (id == R.id.action_move) {
            chooseFolder();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void queueFlag(boolean flagged) {
        queue(flagged ? PendingAction.Type.FLAG : PendingAction.Type.UNFLAG, "");
        message = message.toBuilder().flagged(flagged).build();
        context.store().saveMessage(message);
    }

    private void queue(PendingAction.Type type, String folderId) {
        context.store().account(accountId).ifPresent(account ->
                context.syncEngine().queue(account, type, message.id(), folderId));
        Toast.makeText(this, R.string.message_view_action_queued, Toast.LENGTH_SHORT).show();
    }

    private void moveTo(FolderType type, boolean deleteIfMissing) {
        Folder folder = context.store().listFolders(accountId).stream()
                .filter(value -> value.type() == type).findFirst().orElse(null);
        if (folder != null) {
            queue(PendingAction.Type.MOVE, folder.id());
        } else if (deleteIfMissing) {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setMessage("This account has no Trash folder. Delete this message permanently?")
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                        queue(PendingAction.Type.DELETE, ""); finish();
                    }).show();
            return;
        } else {
            Toast.makeText(this, "This account does not provide that folder", Toast.LENGTH_SHORT).show();
            return;
        }
        finish();
    }

    private void chooseFolder() {
        List<Folder> folders = context.store().listFolders(accountId).stream()
                .filter(Folder::selectable).filter(folder -> !folder.id().equals(message.folderId())).toList();
        String[] names = folders.stream().map(Folder::name).toArray(String[]::new);
        if (names.length == 0) return;
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Move to")
                .setItems(names, (dialog, which) -> { queue(PendingAction.Type.MOVE, folders.get(which).id()); finish(); })
                .show();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_message_view);
        EdgeToEdge.apply(this);
        context = AppContext.of(this);
        accountId = getIntent().getStringExtra(EXTRA_ACCOUNT_ID);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.message_view_title);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(view -> finish());

        String messageId = getIntent().getStringExtra(EXTRA_MESSAGE_ID);
        Optional<Message> found = context.store().message(messageId);
        if (found.isEmpty()) {
            finish();
            return;
        }
        message = found.get();
        render();
        markAsRead();
        findViewById(R.id.message_reply).setOnClickListener(view -> reply());
        findViewById(R.id.message_forward).setOnClickListener(view -> forward());
        findViewById(R.id.view_recipient_details_toggle).setOnClickListener(view -> toggleRecipientDetails());
    }

    private void render() {
        TextView subject = findViewById(R.id.view_subject);
        TextView from = findViewById(R.id.view_from);
        TextView to = findViewById(R.id.view_to);
        TextView date = findViewById(R.id.view_date);
        TextView body = findViewById(R.id.view_body);
        LinearLayout attachmentsLayout = findViewById(R.id.view_attachments);

        subject.setText(message.subject() == null || message.subject().isBlank()
                ? getString(R.string.message_view_subject) : message.subject());
        from.setText(getString(R.string.message_view_from_value, message.from().format()));
        String recipientText = recipients();
        to.setText(getString(R.string.message_view_to_value, recipientText.isBlank()
                ? getString(R.string.message_view_no_recipient) : recipientText));
        TextView cc = findViewById(R.id.view_cc);
        TextView bcc = findViewById(R.id.view_bcc);
        cc.setText(getString(R.string.message_view_cc_value, formatAddresses(message.cc())));
        bcc.setText(getString(R.string.message_view_bcc_value, formatAddresses(message.bcc())));
        findViewById(R.id.view_recipient_details_toggle).setVisibility(
                message.cc().isEmpty() && message.bcc().isEmpty() ? View.GONE : View.VISIBLE);
        date.setText(message.date() == null ? "" : DateTimeFormatter
                .ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
                .withZone(ZoneId.systemDefault()).format(message.date()));
        boolean hasRemoteImages = message.bodyHtml() != null
                && message.bodyHtml().matches("(?is).*<img\\b[^>]*https?://[^>]*>.*");
        findViewById(R.id.view_remote_images_notice).setVisibility(hasRemoteImages ? View.VISIBLE : View.GONE);

        if (message.bodyHtml() != null && !message.bodyHtml().isBlank()) {
            String safe = HtmlSanitizer.sanitize(message.bodyHtml());
            body.setText(Html.fromHtml(safe, Html.FROM_HTML_MODE_COMPACT, new HtmlImageGetter(), null));
            body.setMovementMethod(LinkMovementMethod.getInstance());
        } else {
            body.setText(message.bodyText());
        }

        // Render inline images and attachments
        renderAttachments(attachmentsLayout);
    }

    private void toggleRecipientDetails() {
        TextView cc = findViewById(R.id.view_cc);
        TextView bcc = findViewById(R.id.view_bcc);
        boolean expanded = cc.getVisibility() != View.VISIBLE;
        cc.setVisibility(expanded ? View.VISIBLE : View.GONE);
        bcc.setVisibility(expanded ? View.VISIBLE : View.GONE);
        ((com.google.android.material.button.MaterialButton)
                findViewById(R.id.view_recipient_details_toggle)).setText(
                expanded ? R.string.message_view_hide_details : R.string.message_view_show_details);
    }

    private void renderAttachments(LinearLayout attachmentsLayout) {
        List<Attachment> attachments = message.attachments();
        if (attachments.isEmpty()) {
            findViewById(R.id.view_attachments).setVisibility(View.GONE);
            findViewById(R.id.view_attachment_count).setVisibility(View.GONE);
            return;
        }

        findViewById(R.id.view_attachments).setVisibility(View.VISIBLE);
        TextView attachmentCount = findViewById(R.id.view_attachment_count);
        attachmentCount.setText(getString(R.string.message_view_attachments, attachments.size()));
        attachmentCount.setVisibility(View.VISIBLE);

        // Clear existing attachment views (keep the count)
        int childCount = attachmentsLayout.getChildCount();
        for (int i = childCount - 1; i >= 0; i--) {
            View child = attachmentsLayout.getChildAt(i);
            if (child != attachmentCount) {
                attachmentsLayout.removeView(child);
            }
        }

        // Get account for opening session
        context.io().execute(() -> {
            Optional<Account> accountOpt = context.store().account(accountId);
            if (accountOpt.isEmpty()) return;
            Account account = accountOpt.get();
            
            for (Attachment attachment : attachments) {
                runOnUiThread(() -> {
                    View attachmentView = createAttachmentView(attachment, account);
                    attachmentsLayout.addView(attachmentView, attachmentsLayout.getChildCount() - 1); // Insert before count
                });
            }
        });
    }

    private View createAttachmentView(Attachment attachment, Account account) {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setPadding(16, 8, 16, 8);
        view.setGravity(android.view.Gravity.CENTER_VERTICAL);

        // Icon based on mime type
        ImageView icon = new ImageView(this);
        icon.setLayoutParams(new LinearLayout.LayoutParams(48, 48));
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (attachment.mimeType().startsWith("image/")) {
            // Load inline image
            context.io().execute(() -> {
                try (InputStream is = context.sessions().open(account).attachments().open(attachment)) {
                    Drawable drawable = Drawable.createFromStream(is, attachment.fileName());
                    runOnUiThread(() -> {
                        icon.setImageDrawable(drawable);
                        icon.setVisibility(View.VISIBLE);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> icon.setImageResource(android.R.drawable.ic_menu_gallery));
                }
            });
        } else {
            icon.setImageResource(android.R.drawable.ic_menu_agenda);
        }

        // Info
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        info.setPadding(16, 0, 0, 0);

        TextView name = new TextView(this);
        name.setText(attachment.fileName());
        name.setTextAppearance(android.R.style.TextAppearance_Medium);

        TextView size = new TextView(this);
        size.setText(android.text.format.Formatter.formatFileSize(this, attachment.size()));
        size.setTextAppearance(android.R.style.TextAppearance_Small);
        size.setTextColor(getResources().getColor(android.R.color.secondary_text_dark));

        info.addView(name);
        info.addView(size);

        // Download button
        Button download = new Button(this);
        download.setText(R.string.message_view_download);
        download.setOnClickListener(v -> downloadAttachment(attachment));

        view.addView(icon);
        view.addView(info);
        view.addView(download);

        return view;
    }

private void downloadAttachment(Attachment attachment) {
        TextView attachmentCount = findViewById(R.id.view_attachment_count);
        attachmentCount.setText(R.string.message_view_downloaded);
        context.io().execute(() -> {
            Optional<Account> accountOpt = context.store().account(accountId);
            if (accountOpt.isEmpty()) return;
            Account account = accountOpt.get();
            try (ProviderSession session = context.sessions().open(account)) {
                java.io.File file = new java.io.File(getCacheDir(), attachment.fileName());
                session.attachments().download(attachment, file.toPath());
                runOnUiThread(() -> {
                    attachmentCount.setText(R.string.message_view_saved_to_device);
                    Toast.makeText(this, R.string.message_view_saved_to_device, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    attachmentCount.setText(getString(R.string.message_view_download_failed, e.getMessage()));
                });
            }
        });
    }

    private String recipients() {
        return formatAddresses(message.to());
    }

    private String formatAddresses(List<Address> addresses) {
        StringBuilder sb = new StringBuilder();
        addresses.forEach(address -> sb.append(address.format()).append(", "));
        if (sb.length() > 2) {
            sb.setLength(sb.length() - 2);
        }
        return sb.toString();
    }

    private void markAsRead() {
        if (!message.unread()) {
            return;
        }
        context.store().account(accountId).ifPresent(account -> context.syncEngine().queue(account,
                PendingAction.Type.MARK_READ, message.id(), ""));
        message = message.toBuilder().unread(false).build();
    }

    private void reply() {
        Intent intent = new Intent(this, ComposeActivity.class);
        intent.putExtra(ComposeActivity.EXTRA_ACCOUNT_ID, accountId);
        intent.putExtra(ComposeActivity.EXTRA_TO, message.from().email());
        intent.putExtra(ComposeActivity.EXTRA_SUBJECT,
                message.subject().toLowerCase(java.util.Locale.ROOT).startsWith("re:")
                        ? message.subject() : "Re: " + message.subject());
        intent.putExtra(ComposeActivity.EXTRA_BODY, quote());
        startActivity(intent);
    }

    private void forward() {
        Intent intent = new Intent(this, ComposeActivity.class);
        intent.putExtra(ComposeActivity.EXTRA_ACCOUNT_ID, accountId);
        intent.putExtra(ComposeActivity.EXTRA_SUBJECT,
                message.subject().toLowerCase(java.util.Locale.ROOT).startsWith("fwd:")
                        ? message.subject() : "Fwd: " + message.subject());
        intent.putExtra(ComposeActivity.EXTRA_BODY, quote());
        startActivity(intent);
    }

    private String quote() {
        return "\n\n---\n" + message.from().displayLabel() + ":\n" + message.bodyText();
    }

    /**
     * Image getter for inline images in HTML email.
     */
    private class HtmlImageGetter implements Html.ImageGetter {
        @Override
        public Drawable getDrawable(String source) {
            // source is the CID reference (cid:...)
            if (source == null || !source.startsWith("cid:")) {
                return null;
            }
            String cid = source.substring(4).replace("<", "").replace(">", "");
            
            // Find attachment with matching content ID
            for (Attachment attachment : message.attachments()) {
                if (attachment.contentId().equals(cid) && attachment.mimeType().startsWith("image/")) {
                    context.io().execute(() -> {
                        Optional<Account> accountOpt = context.store().account(accountId);
                        if (accountOpt.isEmpty()) return;
                        Account account = accountOpt.get();
                        try (InputStream is = context.sessions().open(account).attachments().open(attachment)) {
                            Drawable drawable = Drawable.createFromStream(is, attachment.fileName());
                            if (drawable != null) {
                                drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
                                // Note: This won't work directly in ImageGetter since it's async
                                // For now, return null and let it load asynchronously
                            }
                        } catch (Exception e) {
                            // Ignore and fall back
                        }
                    });
                }
            }
            return null;
        }
    }
}
