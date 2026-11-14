/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.imap;

import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import com.graalmail.core.util.Redactor;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.FolderNotFoundException;
import jakarta.mail.MessageRemovedException;
import jakarta.mail.MessagingException;
import java.io.EOFException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

/**
 * Translates mail protocol failures into {@link ProviderException}s with a
 * stable error code and a message that is safe to show to a user.
 */
public final class ImapErrorMapper {

    private ImapErrorMapper() {
    }

    /**
     * Maps a failure to a provider exception.
     *
     * @param error the original failure
     * @param context short description of the operation, for example "connect to the IMAP server"
     * @return the mapped exception
     */
    public static ProviderException map(Throwable error, String context) {
        if (error instanceof ProviderException providerException) {
            return providerException;
        }
        Throwable cause = error;
        String detail = "";
        while (cause != null) {
            String message = cause.getMessage();
            if (message != null && !message.isBlank()) {
                detail = message;
            }
            if (cause instanceof AuthenticationFailedException) {
                String hint = appPasswordHint(chainText(error));
                if (hint != null) {
                    return new ProviderException(ErrorCode.AUTHENTICATION_FAILED, hint, false, cause);
                }
                return new ProviderException(ErrorCode.AUTHENTICATION_FAILED,
                        "Sign in failed while trying to " + context
                                + ". Check the user name and the password or app password.", false, cause);
            }
            if (cause instanceof FolderNotFoundException) {
                return new ProviderException(ErrorCode.MAILBOX_NOT_FOUND,
                        "The mailbox could not be found while trying to " + context + ".", false, cause);
            }
            if (cause instanceof MessageRemovedException) {
                return new ProviderException(ErrorCode.MESSAGE_NOT_FOUND,
                        "The message no longer exists on the server.", false, cause);
            }
            if (cause instanceof UnknownHostException) {
                return new ProviderException(ErrorCode.NETWORK_UNAVAILABLE,
                        "The mail server could not be reached. Check the host name and your internet "
                                + "connection.", true, cause);
            }
            if (cause instanceof SocketTimeoutException) {
                return new ProviderException(ErrorCode.TIMEOUT,
                        "The mail server did not answer in time while trying to " + context + ".", true,
                        cause);
            }
            if (cause instanceof ConnectException || cause instanceof NoRouteToHostException) {
                return new ProviderException(ErrorCode.CONNECTION_REFUSED,
                        "The connection to the mail server was refused while trying to " + context + ".",
                        true, cause);
            }
            if (cause instanceof SSLPeerUnverifiedException) {
                return new ProviderException(ErrorCode.HOSTNAME_VERIFICATION_FAILED,
                        "The certificate of the mail server does not match its host name.", false, cause);
            }
            if (cause instanceof SSLHandshakeException) {
                return new ProviderException(ErrorCode.TLS_ERROR,
                        "The encrypted connection to the mail server could not be established. "
                                + "Check the server security settings and the system clock.", false, cause);
            }
            if (cause instanceof SSLException) {
                return new ProviderException(ErrorCode.TLS_ERROR,
                        "The encrypted connection to the mail server failed. "
                                + "Check the server security settings.", false, cause);
            }
            if (cause instanceof EOFException) {
                return new ProviderException(ErrorCode.NETWORK_UNAVAILABLE,
                        "The connection to the mail server was closed unexpectedly.", true, cause);
            }
            if (cause instanceof SocketException) {
                return new ProviderException(ErrorCode.NETWORK_UNAVAILABLE,
                        "The connection to the mail server was interrupted while trying to " + context + ".",
                        true, cause);
            }
            if (cause.getMessage() != null && cause.getMessage().toLowerCase().contains("starttls")) {
                return new ProviderException(ErrorCode.TLS_ERROR,
                        "The mail server does not support the required STARTTLS encryption. "
                                + "Use a different port or a different security mode.", false, cause);
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        String safeDetail = Redactor.redact(detail);
        return new ProviderException(ErrorCode.PROTOCOL_ERROR,
                "The mail server could not be used to " + context
                        + (safeDetail.isBlank() ? "." : ": " + safeDetail), true, error);
    }

    /** Concatenates the messages of the whole causal chain for marker detection. */
    private static String chainText(Throwable error) {
        StringBuilder text = new StringBuilder();
        Throwable cause = error;
        while (cause != null) {
            if (cause.getMessage() != null) {
                text.append(cause.getMessage()).append('\n');
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        return text.toString().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Recognises provider specific sign-in rejections and returns a message
     * that tells the user exactly what to enter instead, or {@code null}.
     */
    static String appPasswordHint(String chainText) {
        if (chainText.contains("application-specific password")
                || chainText.contains("app password required")
                || chainText.contains("use an app password")) {
            return "The provider rejects the account password for mail clients and requires an "
                    + "app password. Enable 2-Step Verification on the account, generate an app "
                    + "password in the provider's security settings and enter it as the password.";
        }
        if (chainText.contains("basic authentication is disabled")
                || chainText.contains("5.7.139")
                || chainText.contains("basic auth")) {
            return "The mail server refused the password sign-in: basic authentication is disabled "
                    + "for this account or tenant, or the account is protected by multi-factor "
                    + "authentication, which a password cannot complete. Check the account's "
                    + "sign-in settings with the provider.";
        }
        if (chainText.contains("multi-factor") || chainText.contains("mfa")
                || chainText.contains("two-factor") || chainText.contains("2fa")
                || chainText.contains("additional verification")) {
            return "The account requires a second factor that cannot be completed with a plain "
                    + "password over IMAP. If the provider offers app passwords, generate one "
                    + "after enabling 2-Step Verification and enter it as the password.";
        }
        if (chainText.contains("temporarily blocked") || chainText.contains("account is locked")
                || chainText.contains("too many attempts")) {
            return "The provider temporarily blocked the sign-in after repeated attempts. Wait a "
                    + "while and try again; do not retry in a loop.";
        }
        return null;
    }
}
