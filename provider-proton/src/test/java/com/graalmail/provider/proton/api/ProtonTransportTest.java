/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton.api;

import java.io.IOException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProtonTransportTest {

    @Test
    void sendsClientHeadersAndBody() throws Exception {
        try (StubProtonHttp stub = new StubProtonHttp()) {
            stub.respond(200, "{\"Code\":1000}");
            ProtonHttpTransport transport = new DefaultProtonHttpTransport(
                    "http://127.0.0.1:" + stub.port(), "my-app@9", 2_000, 2_000);
            ProtonHttpResponse response = transport.execute(
                    ProtonHttpRequest.post("/auth/info", "{\"Username\":\"u\"}"));
            assertTrue(response.ok());
            StubProtonHttp.Request request = stub.awaitRequest();
            assertEquals("POST", request.method());
            assertEquals("/auth/info", request.path());
            assertEquals("my-app@9", request.headers().get("X-Pm-Appversion"));
            assertEquals("3", request.headers().get("X-Pm-Apiversion"));
            assertEquals("application/json", request.headers().get("Accept"));
            assertTrue(request.headers().get("User-Agent").contains("Firefox"));
            assertEquals("{\"Username\":\"u\"}", request.body());
        }
    }

    @Test
    void authHeadersAreForwarded() throws Exception {
        try (StubProtonHttp stub = new StubProtonHttp()) {
            stub.respond(200, "{\"Code\":1000,\"Labels\":[]}");
            ProtonHttpTransport transport = new DefaultProtonHttpTransport(
                    "http://127.0.0.1:" + stub.port(), "my-app@9", 2_000, 2_000);
            transport.execute(ProtonHttpRequest.get("/labels", "").withHeaders(java.util.Map.of(
                    "X-Pm-Uid", "uid-9", "Authorization", "Bearer tok")));
            StubProtonHttp.Request request = stub.awaitRequest();
            assertEquals("uid-9", request.headers().get("X-Pm-Uid"));
            assertEquals("Bearer tok", request.headers().get("Authorization"));
        }
    }

    @Test
    void unreachableHostThrows() {
        ProtonHttpTransport transport = new DefaultProtonHttpTransport(
                "http://127.0.0.1:1", "my-app@9", 300, 300);
        assertThrows(IOException.class,
                () -> transport.execute(ProtonHttpRequest.get("/labels", "")));
    }

    @Test
    void errorMappingCoversStatuses() {
        assertEquals(com.graalmail.core.error.ErrorCode.AUTHENTICATION_FAILED,
                ProtonApiException.fromResponse("x", 401, "{\"Error\":\"no\"}").code());
        assertEquals(com.graalmail.core.error.ErrorCode.RATE_LIMITED,
                ProtonApiException.fromResponse("x", 429, "").code());
        assertEquals(com.graalmail.core.error.ErrorCode.SERVER_ERROR,
                ProtonApiException.fromResponse("x", 503, "").code());
        assertTrue(ProtonApiException.isTwoFactorRequired(422, "{\"Error\":\"need scope\"}"));
        assertFalse(ProtonApiException.isTwoFactorRequired(401, "{\"Error\":\"need scope\"}"));
    }

    @Test
    void captchaChallengeMapsToActionableError() {
        String body = "{\"Code\":422,\"Error\":\"For security reasons, please complete CAPTCHA\"}";
        assertTrue(ProtonApiException.isCaptchaRequired(422, body));
        assertTrue(ProtonApiException.isCaptchaRequired(400, "{\"Error\":\"captcha required\"}"));
        assertTrue(ProtonApiException.isCaptchaRequired(401, "{\"Error\":\"Complete Captcha\"}"));
        assertFalse(ProtonApiException.isCaptchaRequired(422, "{\"Error\":\"need scope\"}"));
        assertFalse(ProtonApiException.isCaptchaRequired(200, "{\"Error\":\"captcha\"}"));
        assertFalse(ProtonApiException.isCaptchaRequired(503, "{\"Error\":\"captcha\"}"));
        assertFalse(ProtonApiException.isCaptchaRequired(422, ""));

        ProtonApiException mapped = ProtonApiException.fromResponse("log in", 422, body);
        assertInstanceOf(ProtonApiException.CaptchaRequired.class, mapped);
        assertEquals(com.graalmail.core.error.ErrorCode.AUTHENTICATION_FAILED, mapped.code());
        assertFalse(mapped.retryable());
        assertTrue(mapped.userMessage().contains(ProtonApiException.HUMAN_VERIFICATION_LOGIN_URL));

        // A CAPTCHA challenge on a 422 must win over the generic 422 mapping.
        ProtonApiException generic = ProtonApiException.fromResponse(
                "log in", 422, "{\"Error\":\"Invalid credentials\"}");
        assertFalse(generic instanceof ProtonApiException.CaptchaRequired);
    }

    @Test
    void captchaDetailsListAlternativeMethods() {
        String body = "{\"Code\":422,\"Error\":\"Complete CAPTCHA\",\"Details\":"
                + "{\"HumanVerificationMethods\":[\"captcha\",\"email\",\"sms\"]}}";
        ProtonApiException mapped = ProtonApiException.fromResponse("log in", 422, body);
        assertInstanceOf(ProtonApiException.CaptchaRequired.class, mapped);
        assertTrue(mapped.userMessage().contains("captcha, email, sms"));

        ProtonApiException withoutDetails = ProtonApiException.fromResponse(
                "log in", 422, "{\"Error\":\"Complete CAPTCHA\"}");
        assertInstanceOf(ProtonApiException.CaptchaRequired.class, withoutDetails);
        assertFalse(withoutDetails.userMessage().contains("also reports"));
    }

    @Test
    void accountLimitedMapsToCooldownError() {
        String body = "{\"Code\":422,\"Error\":\"Our systems detected unusual activity targeting "
                + "your account. To protect you from potential compromise, we have temporarily "
                + "limited access to it.\"}";
        assertTrue(ProtonApiException.isAccountLimited(422, body));
        assertTrue(ProtonApiException.isAccountLimited(400,
                "{\"Error\":\"temporarily limited\"}"));
        assertTrue(ProtonApiException.isAccountLimited(401,
                "{\"Error\":\"limited access\"}"));
        assertFalse(ProtonApiException.isAccountLimited(422, "{\"Error\":\"Invalid credentials\"}"));
        assertFalse(ProtonApiException.isAccountLimited(200, "{\"Error\":\"unusual activity\"}"));
        assertFalse(ProtonApiException.isAccountLimited(503, "{\"Error\":\"unusual activity\"}"));
        assertFalse(ProtonApiException.isAccountLimited(422, ""));

        ProtonApiException mapped = ProtonApiException.fromResponse("log in", 422, body);
        assertInstanceOf(ProtonApiException.AccountLimited.class, mapped);
        assertEquals(com.graalmail.core.error.ErrorCode.ACCOUNT_LIMITED, mapped.code());
        assertFalse(mapped.retryable());
        assertTrue(mapped.userMessage().contains("temporarily limited"));
    }
}
