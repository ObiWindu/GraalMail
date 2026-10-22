/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.graalmail.android.AppContext;
import com.graalmail.android.R;
import com.graalmail.core.domain.Message;
import com.graalmail.core.text.HtmlSanitizer;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_message_view);
        context = AppContext.of(this);

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
    }

    private void render() {
        TextView subject = findViewById(R.id.view_subject);
        TextView from = findViewById(R.id.view_from);
        TextView to = findViewById(R.id.view_to);
        TextView date = findViewById(R.id.view_date);
        TextView body = findViewById(R.id.view_body);

        subject.setText(message.subject());
        from.setText(message.from().format());
        to.setText(recipients());
        date.setText(message.date() == null ? "" : message.date().toString());

        if (message.bodyHtml() != null && !message.bodyHtml().isBlank()) {
            String safe = HtmlSanitizer.sanitize(message.bodyHtml());
            body.setText(Html.fromHtml(safe, Html.FROM_HTML_MODE_COMPACT));
            body.setMovementMethod(LinkMovementMethod.getInstance());
        } else {
            body.setText(message.bodyText());
        }
        findViewById(R.id.view_attachments).setVisibility(
                message.attachments().isEmpty() ? View.GONE : View.VISIBLE);
        TextView attachmentCount = findViewById(R.id.view_attachment_count);
        attachmentCount.setText(getString(R.string.message_view_attachments,
                message.attachments().size()));
    }

    private String recipients() {
        StringBuilder sb = new StringBuilder();
        message.to().forEach(address -> sb.append(address.format()).append(", "));
        if (sb.length() > 2) {
            sb.setLength(sb.length() - 2);
        }
        return sb.toString();
    }

    private void markAsRead() {
        if (!message.unread()) {
            return;
        }
        context.io().execute(() -> context.store().updateFlags(message.id(), false, message.flagged()));
        message = message.toBuilder().unread(false).build();
    }

    private void reply() {
        Intent intent = new Intent(this, ComposeActivity.class);
        intent.putExtra(ComposeActivity.EXTRA_ACCOUNT_ID, getIntent().getStringExtra(EXTRA_ACCOUNT_ID));
        intent.putExtra(ComposeActivity.EXTRA_TO, message.from().email());
        intent.putExtra(ComposeActivity.EXTRA_SUBJECT,
                message.subject().toLowerCase(java.util.Locale.ROOT).startsWith("re:")
                        ? message.subject() : "Re: " + message.subject());
        intent.putExtra(ComposeActivity.EXTRA_BODY, quote());
        startActivity(intent);
    }

    private String quote() {
        return "\n\n---\n" + message.from().displayLabel() + ":\n" + message.bodyText();
    }
}
