/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.microsoft;

import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import java.util.Locale;

/**
 * Maps Microsoft error responses (IMAP, SMTP, OAuth and Graph) onto the
 * provider neutral {@link ErrorCode} values.
 */
public final class MicrosoftErrorMapper {

    /** Message used when the tenant administrator disabled IMAP or SMTP access. */
    public static final String POLICY_MESSAGE =
            "Access to IMAP or SMTP for this mailbox appears to be disabled by the tenant "
                    + "administrator. An administrator has to enable the IMAP.AccessAsUser.All and "
                    + "SMTP.Send permissions for the registered Azure application and grant admin "
                    + "consent before a third party client can connect.";

    private MicrosoftErrorMapper() {
    }

    /**
     * Classifies a server or OAuth error message.
     *
     * @param message the raw error text, may be {@code null}
     * @return the exception that should be surfaced to the user
     */
    public static ProviderException classify(String message) {
        return classify(message, ErrorCode.UNKNOWN);
    }

    /**
     * Classifies a server or OAuth error message, keeping the given code when
     * nothing more specific matches.
     *
     * @param message the raw error text, may be {@code null}
     * @param fallbackCode the code to use when the message is not recognised
     * @return the exception that should be surfaced to the user
     */
    public static ProviderException classify(String message, ErrorCode fallbackCode) {
        String text = message == null ? "" : message;
        String lower = text.toLowerCase(Locale.ROOT);

        if (isRevokedOrExpired(lower)) {
            return ProviderException.authExpired(
                    "The Microsoft authorization is no longer valid, please sign in again.");
        }
        if (isPolicyBlock(lower)) {
            return new ProviderException(ErrorCode.PERMISSION_DENIED, POLICY_MESSAGE, false);
        }
        if (isAuthenticationFailure(lower)) {
            return ProviderException.authenticationFailed(
                    "Microsoft rejected the credentials: " + summarize(text));
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return new ProviderException(ErrorCode.TIMEOUT, "Microsoft did not answer in time.", true);
        }
        if (lower.contains("connect") || lower.contains("network") || lower.contains("unreachable")) {
            return ProviderException.network("Could not reach Microsoft: " + summarize(text), null);
        }
        return new ProviderException(fallbackCode, "Microsoft reported an error: " + summarize(text),
                false);
    }

    /**
     * Maps a Graph HTTP status code onto an error.
     *
     * @param status the HTTP status code
     * @param body the response body, may be {@code null}
     * @return the exception to throw
     */
    public static ProviderException fromHttpStatus(int status, String body) {
        String detail = summarize(body == null ? "" : body);
        return switch (status) {
            case 401 -> ProviderException.authExpired(
                    "The Microsoft Graph session is no longer valid, please sign in again.");
            case 403 -> new ProviderException(ErrorCode.PERMISSION_DENIED,
                    "Microsoft Graph denied the request: " + detail
                            + ". The registered application may be missing the required "
                            + "permissions or admin consent.", false);
            case 404 -> new ProviderException(ErrorCode.MAILBOX_NOT_FOUND,
                    "Microsoft Graph could not find the requested resource: " + detail, false);
            case 429 -> new ProviderException(ErrorCode.RATE_LIMITED,
                    "Microsoft Graph rate limit reached, please retry later.", true);
            default -> {
                if (status >= 500) {
                    yield new ProviderException(ErrorCode.SERVER_ERROR,
                            "Microsoft Graph is temporarily unavailable: " + detail, true);
                }
                yield new ProviderException(ErrorCode.UNKNOWN,
                        "Microsoft Graph returned HTTP " + status + ": " + detail, false);
            }
        };
    }

    private static boolean isRevokedOrExpired(String lower) {
        return lower.contains("invalid_grant")
                || lower.contains("aadsts50076")
                || lower.contains("aadsts50100")
                || lower.contains("has been revoked")
                || lower.contains("refresh token is invalid")
                || lower.contains("expired or was revoked")
                || lower.contains("token has expired")
                || lower.contains("expired token")
                || lower.contains("invalid refresh token");
    }

    private static boolean isPolicyBlock(String lower) {
        return lower.contains("access is denied")
                || lower.contains("not permitted")
                || lower.contains("access denied")
                || lower.contains("aadsts65001")
                || lower.contains("aadsts53003")
                || lower.contains("policy")
                || lower.contains("disabled")
                || lower.contains("blocked")
                || lower.contains("tenant administrator");
    }

    private static boolean isAuthenticationFailure(String lower) {
        return lower.contains("[authenticationfailed]")
                || lower.contains("authentication failed")
                || lower.contains("invalid credentials")
                || lower.contains("login failed")
                || lower.contains("aadsts50126")
                || lower.contains("aadsts7000215")
                || lower.contains("unauthorized client");
    }

    private static String summarize(String text) {
        String value = text.replaceAll("\\s+", " ").trim();
        return value.length() <= 300 ? value : value.substring(0, 300) + "...";
    }
}
