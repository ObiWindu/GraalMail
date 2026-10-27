/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;

/**
 * Maps Proton API failures to provider errors.
 *
 * <p>Proton answers with HTTP status codes plus a JSON body of the form
 * {@code {"Code":…, "Error":"…"}}. A 422 with a two-factor scope means the
 * account needs a second factor code.</p>
 */
import java.util.List;

public class ProtonApiException extends ProviderException {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProtonApiException(ErrorCode code, String message, boolean retryable, Throwable cause) {
        super(code, message, retryable, cause);
    }

    /** True when the response demands a two-factor code. */
    public static boolean isTwoFactorRequired(int status, String body) {
        if (status != 422) {
            return false;
        }
        String error = errorText(body).toLowerCase(java.util.Locale.ROOT);
        return error.contains("two-factor") || error.contains("2fa")
                || error.contains("two factor") || error.contains("scope");
    }

    /** Official Proton login page where a human-verification check can be completed. */
    public static final String HUMAN_VERIFICATION_LOGIN_URL = "https://account.proton.me/login";

    /** True when the response says the account is temporarily limited. */
    public static boolean isAccountLimited(int status, String body) {
        if (status < 400 || status >= 500) {
            return false;
        }
        String error = errorText(body).toLowerCase(java.util.Locale.ROOT);
        return error.contains("unusual activity")
                || error.contains("temporarily limited")
                || error.contains("limited access")
                || error.contains("potential compromise");
    }

    /**
     * A temporarily limited account. Proton locks the account server-side
     * after detecting unusual activity; further sign-in attempts extend the
     * lock, so this is deliberately not retryable. The user must wait it out
     * (typically up to a day), use Proton's official apps meanwhile, and
     * contact Proton support if it persists.
     */
    public static final class AccountLimited extends ProtonApiException {
        private AccountLimited(String serverDetail) {
            super(ErrorCode.ACCOUNT_LIMITED,
                    "Proton has temporarily limited access to this account after detecting "
                            + "unusual activity"
                            + (serverDetail.isBlank() ? "." : ": " + serverDetail)
                            + " Do not keep signing in — further attempts extend the lock. "
                            + "Wait up to a day, use Proton's official apps meanwhile, and "
                            + "contact Proton support if it persists. Background sync pauses "
                            + "automatically until the lock lifts.",
                    false, null);
        }
    }
    /** True when the response demands a CAPTCHA / human-verification check. */
    public static boolean isCaptchaRequired(int status, String body) {
        if (status < 400 || status >= 500) {
            return false;
        }
        return errorText(body).toLowerCase(java.util.Locale.ROOT).contains("captcha");
    }

    /**
     * A Proton human-verification challenge. There is deliberately no
     * in-app solver: Proton's verification is a behavioral challenge iframe
     * (see their open {@code @proton/challenge} client package) that must
     * observe a real web form, and the standalone captcha API no longer
     * exists. The user completes the check in a browser once, then retries.
     * Deliberately not retryable: blind retries only burn login attempts and
     * can lock the account.
     */
    public static final class CaptchaRequired extends ProtonApiException {
        private CaptchaRequired(String serverDetail, List<String> methods) {
            super(ErrorCode.AUTHENTICATION_FAILED,
                    "Proton is asking for CAPTCHA verification for this sign-in"
                            + (serverDetail.isBlank() ? "." : ": " + serverDetail)
                            + (methods.isEmpty() ? ""
                                    : " Proton also reports these verification methods: "
                                            + String.join(", ", methods) + ".")
                            + " Open " + HUMAN_VERIFICATION_LOGIN_URL + " in a browser, "
                            + "sign in once to complete the check, then try again in GraalMail.",
                    false, null);
        }
    }

    public static ProtonApiException fromResponse(String action, int status, String body) {
        if (isCaptchaRequired(status, body)) {
            return new CaptchaRequired(errorText(body), verificationMethods(body));
        }
        if (isAccountLimited(status, body)) {
            return new AccountLimited(errorText(body));
        }
        String detail = errorText(body);
        String message = detail.isBlank()
                ? "Proton answered with status " + status + " while " + action
                : "Proton refused to " + action + ": " + detail;
        return switch (status) {
            case 401 -> new ProtonApiException(ErrorCode.AUTHENTICATION_FAILED, message, false, null);
            case 403 -> new ProtonApiException(ErrorCode.PERMISSION_DENIED, message, false, null);
            case 404 -> new ProtonApiException(ErrorCode.MAILBOX_NOT_FOUND, message, false, null);
            case 422 -> new ProtonApiException(ErrorCode.AUTHENTICATION_FAILED, message, false, null);
            case 429 -> new ProtonApiException(ErrorCode.RATE_LIMITED, message, true, null);
            default -> {
                if (status >= 500) {
                    yield new ProtonApiException(ErrorCode.SERVER_ERROR, message, true, null);
                }
                yield new ProtonApiException(ErrorCode.PROTOCOL_ERROR, message, false, null);
            }
        };
    }

    public static ProtonApiException network(String action, java.io.IOException cause) {
        return new ProtonApiException(ErrorCode.CONNECTION_REFUSED,
                "Proton could not be reached while " + action + ": " + cause.getMessage(), true, cause);
    }

    /** Alternative verification methods Proton lists in the error Details, if any. */
    static List<String> verificationMethods(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            JsonNode methods = MAPPER.readTree(body).path("Details").path("HumanVerificationMethods");
            if (!methods.isArray()) {
                return List.of();
            }
            List<String> result = new java.util.ArrayList<>();
            for (JsonNode method : methods) {
                if (method.isTextual() && !method.asText().isBlank()) {
                    result.add(method.asText().strip());
                }
            }
            return List.copyOf(result);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    static String errorText(String body) {        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode error = root.path("Error");
            if (error.isTextual()) {
                return error.asText().strip();
            }
        } catch (Exception ignored) {
            // fall through to raw body
        }
        return "";
    }
}
