/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.graalmail.android.R;

/**
 * Receives the redirect of the OAuth sign in.
 *
 * <p>The activity is the target of the {@code graalmail://oauth} redirect. It
 * extracts the code from the redirect and hands it to the setup screen, which
 * completes the token exchange.</p>
 */
public final class OAuthRedirectActivity extends AppCompatActivity {

    /** The extra that carries the authorization code. */
    public static final String EXTRA_CODE = "code";
    /** The extra that carries the state of the request. */
    public static final String EXTRA_STATE = "state";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri data = getIntent().getData();
        String code = data == null ? getIntent().getStringExtra(EXTRA_CODE) : data.getQueryParameter("code");
        String state = data == null ? getIntent().getStringExtra(EXTRA_STATE) : data.getQueryParameter("state");
        Intent result = new Intent();
        result.putExtra(EXTRA_CODE, code == null ? "" : code);
        result.putExtra(EXTRA_STATE, state == null ? "" : state);
        setResult(RESULT_OK, result);
        Toast.makeText(this, R.string.setup_oauth_redirect_received, Toast.LENGTH_SHORT).show();
        finish();
    }
}
