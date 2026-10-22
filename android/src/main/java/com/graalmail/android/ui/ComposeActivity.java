/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.graalmail.android.AppContext;
import com.graalmail.android.R;
import com.graalmail.android.util.ComposeValidator;
import com.graalmail.core.domain.Account;
import com.graalmail.core.domain.MessageDraft;
import com.graalmail.core.error.ProviderException;
import com.graalmail.core.text.AddressParser;
import com.graalmail.core.util.Log;
import com.graalmail.provider.api.ProviderSession;
import com.graalmail.provider.api.SendResult;

import org.slf4j.Logger;

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

    private AppContext context;
    private String accountId;
    private EditText toField;
    private EditText subjectField;
    private EditText bodyField;

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
        toolbar.setNavigationOnClickListener(view -> finish());

        toField = findViewById(R.id.compose_to);
        subjectField = findViewById(R.id.compose_subject);
        bodyField = findViewById(R.id.compose_body);

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

    private void send() {
        ComposeValidator.Result validation = ComposeValidator.validate(
                toField.getText().toString(), "", "", bodyField.getText().toString());
        if (!validation.isValid()) {
            ComposeValidator.Problem problem = validation.problems().get(0);
            Toast.makeText(this, problem.message(), Toast.LENGTH_LONG).show();
            return;
        }
        TextView status = findViewById(R.id.compose_status);
        status.setText(R.string.compose_sending);
        MessageDraft draft = MessageDraft.builder()
                .accountId(accountId)
                .to(AddressParser.parseList(toField.getText().toString()))
                .subject(subjectField.getText().toString())
                .bodyText(bodyField.getText().toString())
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
        MessageDraft draft = MessageDraft.builder()
                .accountId(accountId)
                .to(AddressParser.parseList(toField.getText().toString()))
                .subject(subjectField.getText().toString())
                .bodyText(bodyField.getText().toString())
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
}
