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
}
