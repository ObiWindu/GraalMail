/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Authenticated client for the Proton Mail API.
 *
 * <p>Refreshes the access token transparently: a single retry after refresh is
 * attempted when an authenticated call answers 401.</p>
 */
public final class ProtonApiClient {

    /** Client identification sent as {@code X-Pm-Appversion}. */
    public static final String DEFAULT_APP_VERSION = "web-mail@5.0.140.0";

    /** System label identifiers. */
    public static final String LABEL_INBOX = "0";
    public static final String LABEL_ALL_DRAFTS = "1";
    public static final String LABEL_ALL_SENT = "2";
    public static final String LABEL_TRASH = "3";
    public static final String LABEL_SPAM = "4";
    public static final String LABEL_ALL_MAIL = "5";
    public static final String LABEL_ARCHIVE = "6";
    public static final String LABEL_SENT = "7";
    public static final String LABEL_DRAFTS = "8";
    public static final String LABEL_STARRED = "10";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ProtonHttpTransport transport;
    private final ProtonAuth auth;
    private ProtonAuth.Tokens tokens;

    public ProtonApiClient(ProtonHttpTransport transport, ProtonAuth auth, ProtonAuth.Tokens tokens) {
        this.transport = transport;
        this.auth = auth;
        this.tokens = tokens;
    }

    public ProtonAuth.Tokens tokens() {
        return tokens;
    }

    // Models.

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PrivateKey(String ID, String Fingerprint, int Primary, int Active,
                             String PrivateKey, String Token, String Signature) {
        public PrivateKey {
            ID = orEmpty(ID);
            Fingerprint = orEmpty(Fingerprint);
            PrivateKey = orEmpty(PrivateKey);
            Token = orEmpty(Token);
            Signature = orEmpty(Signature);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(String ID, String Name, List<PrivateKey> Keys) {
        public User {
            ID = orEmpty(ID);
            Name = orEmpty(Name);
            Keys = Keys == null ? List.of() : List.copyOf(Keys);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Address(String ID, String Email, String DisplayName, int Status,
                          int Send, List<PrivateKey> Keys) {
        public Address {
            ID = orEmpty(ID);
            Email = orEmpty(Email);
            DisplayName = orEmpty(DisplayName);
            Keys = Keys == null ? List.of() : List.copyOf(Keys);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Label(String ID, String Name, String Color, int Type,
                        int Display, int Notify, int Order) {
        public Label {
            ID = orEmpty(ID);
            Name = orEmpty(Name);
            Color = orEmpty(Color);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KeySalt(String ID, String KeySalt) {
        public KeySalt {
            ID = orEmpty(ID);
            KeySalt = orEmpty(KeySalt);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MessageAddress(String Address, String Name) {
        public MessageAddress {
            Address = orEmpty(Address);
            Name = orEmpty(Name);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Attachment(String ID, String Name, String MIMEType, long Size) {
        public Attachment {
            ID = orEmpty(ID);
            Name = orEmpty(Name);
            MIMEType = orEmpty(MIMEType);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String ID, String ConversationID, String Subject, int Unread,
                          int Type, MessageAddress Sender, List<MessageAddress> ToList,
                          List<MessageAddress> CCList, List<MessageAddress> BCCList,
                          long Time, long Size, int NumAttachments, int IsEncrypted,
                          String Body, String MIMEType, String Header,
                          List<Attachment> Attachments, List<String> LabelIDs,
                          String AddressID, String ExternalID) {
        public Message {
            ID = orEmpty(ID);
            ConversationID = orEmpty(ConversationID);
            Subject = orEmpty(Subject);
            ToList = ToList == null ? List.of() : List.copyOf(ToList);
            CCList = CCList == null ? List.of() : List.copyOf(CCList);
            BCCList = BCCList == null ? List.of() : List.copyOf(BCCList);
            Body = orEmpty(Body);
            MIMEType = orEmpty(MIMEType);
            Header = orEmpty(Header);
            Attachments = Attachments == null ? List.of() : List.copyOf(Attachments);
            LabelIDs = LabelIDs == null ? List.of() : List.copyOf(LabelIDs);
            AddressID = orEmpty(AddressID);
            ExternalID = orEmpty(ExternalID);
        }

        public boolean unread() {
            return Unread != 0;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LabelCount(String LabelID, int Total, int Unread) {
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    // Calls.

    public User currentUser() {
        return read(authenticated("GET", "/users", ""), "load the Proton user", User.class, "User");
    }

    public List<Address> addresses() {
        return list(authenticated("GET", "/addresses", ""), "load Proton addresses",
                Address.class, "Addresses");
    }

    public List<Label> labels() {
        return list(authenticated("GET", "/labels", ""), "load Proton folders",
                Label.class, "Labels");
    }

    public List<KeySalt> keySalts() {
        return list(authenticated("GET", "/keys/salts", ""), "load Proton key salts",
                KeySalt.class, "KeySalts");
    }

    public List<LabelCount> messageCounts(String addressId) {
        String query = addressId == null || addressId.isBlank() ? ""
                : "Address=" + urlEncode(addressId);
        return list(authenticated("GET", "/messages/count", query), "count Proton messages",
                LabelCount.class, "Counts");
    }

    /** Lists messages of a label, newest first. Pages are zero-based. */
    public List<Message> listMessages(String labelId, int page, int pageSize) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("Label", labelId);
        query.put("Page", Integer.toString(Math.max(0, page)));
        query.put("PageSize", Integer.toString(Math.max(1, pageSize)));
        query.put("Sort", "Time");
        query.put("Desc", "1");
        query.put("Limit", Integer.toString(Math.max(1, pageSize)));
        return list(authenticated("GET", "/messages", encodeQuery(query)), "list Proton messages",
                Message.class, "Messages");
    }

    public Message getMessage(String id) {
        return read(authenticated("GET", "/messages/" + urlEncode(id), ""), "load a Proton message",
                Message.class, "Message");
    }

    public void setRead(List<String> ids, boolean read) {
        if (ids.isEmpty()) {
            return;
        }
        execute(authenticated("PUT", read ? "/messages/read" : "/messages/unread", "",
                json(Map.of("IDs", ids))), "mark Proton messages");
    }

    public void setLabel(List<String> ids, String labelId) {
        if (ids.isEmpty()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("IDs", ids);
        payload.put("LabelID", labelId);
        execute(authenticated("PUT", "/messages/label", "", json(payload)), "move Proton messages");
    }

    public void unsetLabel(List<String> ids, String labelId) {
        if (ids.isEmpty()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("IDs", ids);
        payload.put("LabelID", labelId);
        execute(authenticated("PUT", "/messages/unlabel", "", json(payload)),
                "remove Proton messages from a folder");
    }

    public void delete(List<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        execute(authenticated("PUT", "/messages/delete", "", json(Map.of("IDs", ids))),
                "delete Proton messages");
    }

    // Plumbing.

    private record AuthedRequest(String method, String path, String query, String body) {
    }

    private AuthedRequest authenticated(String method, String path, String query) {
        return new AuthedRequest(method, path, query, null);
    }

    private AuthedRequest authenticated(String method, String path, String query, String body) {
        return new AuthedRequest(method, path, query, body);
    }

    private ProtonHttpResponse execute(AuthedRequest call, String action) {
        Map<String, String> headers = Map.of(
                "X-Pm-Uid", tokens.uid(),
                "Authorization", "Bearer " + tokens.accessToken());
        ProtonHttpRequest request = new ProtonHttpRequest(
                call.method(), call.path(), call.query(), call.body(), headers);
        try {
            ProtonHttpResponse response = transport.execute(request);
            if (response.status() == 401 && !tokens.refreshToken().isBlank()) {
                tokens = auth.refresh(tokens.uid(), tokens.refreshToken());
                Map<String, String> retryHeaders = Map.of(
                        "X-Pm-Uid", tokens.uid(),
                        "Authorization", "Bearer " + tokens.accessToken());
                response = transport.execute(new ProtonHttpRequest(
                        call.method(), call.path(), call.query(), call.body(), retryHeaders));
            }
            return response;
        } catch (IOException e) {
            throw ProtonApiException.network(action, e);
        }
    }

    private <T> T read(AuthedRequest call, String action, Class<T> type, String field) {
        ProtonHttpResponse response = execute(call, action);
        if (!response.ok()) {
            throw ProtonApiException.fromResponse(action, response.status(), response.body());
        }
        try {
            JsonNode node = MAPPER.readTree(response.body()).path(field);
            return MAPPER.treeToValue(node, type);
        } catch (Exception e) {
            throw new ProviderException(ErrorCode.PROTOCOL_ERROR,
                    "Proton answered with an unreadable response while trying to " + action + ".",
                    false, e);
        }
    }

    private <T> List<T> list(AuthedRequest call, String action, Class<T> type, String field) {
        ProtonHttpResponse response = execute(call, action);
        if (!response.ok()) {
            throw ProtonApiException.fromResponse(action, response.status(), response.body());
        }
        try {
            JsonNode node = MAPPER.readTree(response.body()).path(field);
            List<T> result = new ArrayList<>();
            if (node.isArray()) {
                for (JsonNode item : node) {
                    result.add(MAPPER.treeToValue(item, type));
                }
            }
            return result;
        } catch (Exception e) {
            throw new ProviderException(ErrorCode.PROTOCOL_ERROR,
                    "Proton answered with an unreadable response while trying to " + action + ".",
                    false, e);
        }
    }

    private static String json(Object payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to encode Proton request", e);
        }
    }

    private static String encodeQuery(Map<String, String> query) {
        StringBuilder out = new StringBuilder();
        for (var entry : query.entrySet()) {
            if (!out.isEmpty()) {
                out.append('&');
            }
            out.append(urlEncode(entry.getKey())).append('=').append(urlEncode(entry.getValue()));
        }
        return out.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
