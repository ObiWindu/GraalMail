/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.microsoft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.graalmail.core.domain.Address;
import com.graalmail.core.domain.MessageDraft;
import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A small client for the Microsoft Graph mail API.
 *
 * <p>The client supports listing mail folders, listing and searching messages,
 * reading single messages, changing flags, moving and deleting messages and
 * sending messages. All requests go through an injectable
 * {@link GraphHttpTransport} so that the client can be tested without network
 * access.</p>
 */
public final class MicrosoftGraphClient {

    /** The Microsoft Graph base URL. */
    public static final String BASE_URL = "https://graph.microsoft.com/v1.0";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final Supplier<String> accessTokenSupplier;
    private final GraphHttpTransport transport;

    /**
     * Creates a client that talks to the public Graph endpoint.
     *
     * @param accessTokenSupplier supplies the current access token
     * @param transport the HTTP transport
     */
    public MicrosoftGraphClient(Supplier<String> accessTokenSupplier, GraphHttpTransport transport) {
        this(BASE_URL, accessTokenSupplier, transport);
    }

    /**
     * Creates a client.
     *
     * @param baseUrl the Graph base URL
     * @param accessTokenSupplier supplies the current access token
     * @param transport the HTTP transport
     */
    public MicrosoftGraphClient(String baseUrl, Supplier<String> accessTokenSupplier,
                                GraphHttpTransport transport) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? BASE_URL : baseUrl;
        this.accessTokenSupplier = accessTokenSupplier;
        this.transport = transport;
    }

    /**
     * Builds the absolute URL of a Graph request.
     *
     * @param path the path below the base URL
     * @param query the query parameters, may be {@code null}
     * @return the absolute URL
     */
    public String buildUrl(String path, Map<String, String> query) {
        StringBuilder sb = new StringBuilder(baseUrl);
        sb.append(path.startsWith("/") ? path : "/" + path);
        if (query != null && !query.isEmpty()) {
            sb.append('?');
            boolean first = true;
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (!first) {
                    sb.append('&');
                }
                first = false;
                sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
                sb.append('=');
                sb.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            }
        }
        return sb.toString();
    }

    /**
     * Lists the mail folders of the signed in user.
     *
     * @return the folders
     */
    public List<GraphFolder> listMailFolders() {
        JsonNode root = get("/me/mailFolders");
        List<GraphFolder> folders = new ArrayList<>();
        for (JsonNode node : root.path("value")) {
            folders.add(new GraphFolder(node.path("id").asText(""),
                    node.path("displayName").asText(""),
                    node.path("wellKnownName").asText(""),
                    node.path("totalItemCount").asInt(0),
                    node.path("unreadItemCount").asInt(0)));
        }
        return folders;
    }

    /**
     * Lists the messages of a folder.
     *
     * @param folderId the Graph folder id
     * @param page the zero based page index
     * @param pageSize the number of messages per page
     * @return the messages, newest first
     */
    public List<GraphMessage> listMessages(String folderId, int page, int pageSize) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("$top", String.valueOf(pageSize));
        query.put("$skip", String.valueOf(Math.max(page, 0) * pageSize));
        query.put("$orderby", "receivedDateTime desc");
        query.put("$select", "id,subject,from,toRecipients,receivedDateTime,isRead,flag,"
                + "bodyPreview,size,hasAttachments,internetMessageId");
        JsonNode root = get("/me/mailFolders/" + encode(folderId) + "/messages", query);
        List<GraphMessage> messages = new ArrayList<>();
        for (JsonNode node : root.path("value")) {
            messages.add(toMessage(node));
        }
        return messages;
    }

    /**
     * Searches the mail box.
     *
     * @param term the Graph search expression
     * @param limit the maximum number of results
     * @return the matching messages
     */
    public List<GraphMessage> search(String term, int limit) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("$search", "\"" + term + "\"");
        query.put("$top", String.valueOf(limit));
        query.put("$select", "id,subject,from,toRecipients,receivedDateTime,isRead,flag,"
                + "bodyPreview,size,hasAttachments,internetMessageId");
        JsonNode root = get("/me/messages", query);
        List<GraphMessage> messages = new ArrayList<>();
        for (JsonNode node : root.path("value")) {
            messages.add(toMessage(node));
        }
        return messages;
    }

    /**
     * Reads a single message including its body.
     *
     * @param messageId the Graph message id
     * @return the message
     */
    public GraphMessage getMessage(String messageId) {
        JsonNode node = get("/me/messages/" + encode(messageId));
        return toMessage(node);
    }

    /**
     * Changes the flags of a message.
     *
     * @param messageId the Graph message id
     * @param read the new read state
     * @param flagged the new flagged state
     */
    public void setFlags(String messageId, Boolean read, Boolean flagged) {
        ObjectNode body = MAPPER.createObjectNode();
        if (read != null) {
            body.put("isRead", read);
        }
        if (flagged != null) {
            body.put("flag", flagged ? "flagged" : "notFlagged");
        }
        patch("/me/messages/" + encode(messageId), body.toString());
    }

    /**
     * Moves a message into another folder.
     *
     * @param messageId the Graph message id
     * @param destinationFolderId the Graph folder id
     */
    public void move(String messageId, String destinationFolderId) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("destinationId", destinationFolderId);
        post("/me/messages/" + encode(messageId) + "/move", body.toString());
    }

    /**
     * Deletes a message.
     *
     * @param messageId the Graph message id
     */
    public void delete(String messageId) {
        request("DELETE", "/me/messages/" + encode(messageId), null, null);
    }

    /**
     * Sends a message through the Graph sendMail endpoint.
     *
     * @param draft the draft to send
     * @param from the sender address
     * @return the Graph message id of the sent message
     */
    public String sendMessage(MessageDraft draft, String from) {
        ObjectNode message = MAPPER.createObjectNode();
        ObjectNode body = MAPPER.createObjectNode();
        body.put("contentType", draft.bodyHtml().isBlank() ? "Text" : "HTML");
        body.put("content", draft.bodyHtml().isBlank() ? draft.bodyText() : draft.bodyHtml());
        message.set("body", body);
        message.put("subject", draft.subject());
        message.set("toRecipients", recipients(draft.to()));
        if (!draft.cc().isEmpty()) {
            message.set("ccRecipients", recipients(draft.cc()));
        }
        if (!draft.bcc().isEmpty()) {
            message.set("bccRecipients", recipients(draft.bcc()));
        }
        ObjectNode envelope = MAPPER.createObjectNode();
        ObjectNode sender = MAPPER.createObjectNode();
        sender.set("emailAddress", MAPPER.createObjectNode().put("address", from));
        envelope.set("message", message);
        envelope.set("sender", sender);
        post("/me/sendMail", envelope.toString());
        return "";
    }

    /**
     * Creates a draft message in the drafts folder.
     *
     * @param draft the draft to store
     * @param from the sender address
     * @return the Graph message id of the draft
     */
    public String createDraft(MessageDraft draft, String from) {
        ObjectNode message = MAPPER.createObjectNode();
        ObjectNode body = MAPPER.createObjectNode();
        body.put("contentType", draft.bodyHtml().isBlank() ? "Text" : "HTML");
        body.put("content", draft.bodyHtml().isBlank() ? draft.bodyText() : draft.bodyHtml());
        message.set("body", body);
        message.put("subject", draft.subject());
        message.set("toRecipients", recipients(draft.to()));
        message.put("isDraft", true);
        JsonNode created = post("/me/messages", message.toString());
        return created.path("id").asText("");
    }

    /**
     * Lists the attachments of a message.
     *
     * @param messageId the Graph message id
     * @return the attachments
     */
    public List<GraphAttachment> listAttachments(String messageId) {
        JsonNode root = get("/me/messages/" + encode(messageId) + "/attachments");
        List<GraphAttachment> attachments = new ArrayList<>();
        for (JsonNode node : root.path("value")) {
            attachments.add(new GraphAttachment(node.path("id").asText(""),
                    node.path("name").asText(""),
                    node.path("contentType").asText("application/octet-stream"),
                    node.path("size").asLong(0),
                    node.path("isInline").asBoolean(false)));
        }
        return attachments;
    }

    /**
     * Downloads the content of an attachment.
     *
     * @param messageId the Graph message id
     * @param attachmentId the Graph attachment id
     * @return the raw attachment content
     */
    public byte[] downloadAttachment(String messageId, String attachmentId) {
        String url = buildUrl("/me/messages/" + encode(messageId) + "/attachments/"
                + encode(attachmentId) + "/$value", Map.of());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + accessTokenSupplier.get());
        GraphHttpResponse response;
        try {
            response = transport.execute(new GraphHttpRequest("GET", url, Map.of(), null, headers));
        } catch (IOException e) {
            throw ProviderException.network("Could not reach the Microsoft Graph service: "
                    + e.getMessage(), e);
        }
        if (response.status() >= 400) {
            throw MicrosoftErrorMapper.fromHttpStatus(response.status(), response.body());
        }
        return response.content() == null ? new byte[0] : response.content();
    }

    private ArrayNode recipients(List<Address> addresses) {
        ArrayNode array = MAPPER.createArrayNode();
        for (Address address : addresses) {
            ObjectNode entry = MAPPER.createObjectNode();
            entry.set("emailAddress", MAPPER.createObjectNode().put("address", address.email()));
            array.add(entry);
        }
        return array;
    }

    private GraphMessage toMessage(JsonNode node) {
        GraphMessage message = new GraphMessage();
        message.id = node.path("id").asText("");
        message.subject = node.path("subject").asText("");
        message.bodyPreview = node.path("bodyPreview").asText("");
        message.internetMessageId = node.path("internetMessageId").asText("");
        message.read = node.path("isRead").asBoolean(false);
        message.flagged = "flagged".equalsIgnoreCase(node.path("flag").asText(""));
        message.hasAttachments = node.path("hasAttachments").asBoolean(false);
        message.size = node.path("size").asLong(0);
        message.received = parseInstant(node.path("receivedDateTime").asText(""));
        JsonNode from = node.path("from").path("emailAddress");
        if (!from.isMissingNode()) {
            message.from = new Address(from.path("name").asText(""), from.path("address").asText(""));
        }
        for (JsonNode recipient : node.path("toRecipients")) {
            JsonNode address = recipient.path("emailAddress");
            message.to.add(new Address(address.path("name").asText(""), address.path("address").asText("")));
        }
        JsonNode body = node.path("body");
        if (!body.isMissingNode()) {
            message.bodyText = body.path("content").asText("");
            message.bodyHtml = "html".equalsIgnoreCase(body.path("contentType").asText(""))
                    ? body.path("content").asText("") : "";
        }
        return message;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private JsonNode get(String path) {
        return get(path, Map.of());
    }

    private JsonNode get(String path, Map<String, String> query) {
        return request("GET", path, query, null);
    }

    private JsonNode post(String path, String body) {
        return request("POST", path, Map.of(), body);
    }

    private void patch(String path, String body) {
        request("PATCH", path, Map.of(), body);
    }

    private JsonNode request(String method, String path, Map<String, String> query, String body) {
        String url = buildUrl(path, query);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + accessTokenSupplier.get());
        GraphHttpResponse response;
        try {
            response = transport.execute(new GraphHttpRequest(method, url, query, body, headers));
        } catch (IOException e) {
            throw ProviderException.network("Could not reach the Microsoft Graph service: "
                    + e.getMessage(), e);
        }
        if (response.status() >= 400) {
            throw MicrosoftErrorMapper.fromHttpStatus(response.status(), response.body());
        }
        String payload = response.body();
        if (payload == null || payload.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(payload);
        } catch (IOException e) {
            throw new ProviderException(ErrorCode.MALFORMED_RESPONSE,
                    "Microsoft Graph returned a malformed response.", false, e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** A mail folder as reported by Graph. */
    public static final class GraphFolder {
        private final String id;
        private final String displayName;
        private final String wellKnownName;
        private final int totalItemCount;
        private final int unreadItemCount;

        GraphFolder(String id, String displayName, String wellKnownName, int totalItemCount,
                    int unreadItemCount) {
            this.id = id;
            this.displayName = displayName;
            this.wellKnownName = wellKnownName;
            this.totalItemCount = totalItemCount;
            this.unreadItemCount = unreadItemCount;
        }

        public String id() { return id; }
        public String displayName() { return displayName; }
        public String wellKnownName() { return wellKnownName; }
        public int totalItemCount() { return totalItemCount; }
        public int unreadItemCount() { return unreadItemCount; }
    }

    /** An attachment as reported by Graph. */
    public static final class GraphAttachment {
        private final String id;
        private final String name;
        private final String contentType;
        private final long size;
        private final boolean inline;

        GraphAttachment(String id, String name, String contentType, long size, boolean inline) {
            this.id = id;
            this.name = name;
            this.contentType = contentType;
            this.size = size;
            this.inline = inline;
        }

        public String id() { return id; }
        public String name() { return name; }
        public String contentType() { return contentType; }
        public long size() { return size; }
        public boolean inline() { return inline; }
    }

    /** A message as reported by Graph. */    public static final class GraphMessage {
        private String id = "";
        private String subject = "";
        private String bodyPreview = "";
        private String bodyText = "";
        private String bodyHtml = "";
        private String internetMessageId = "";
        private boolean read;
        private boolean flagged;
        private boolean hasAttachments;
        private long size;
        private Instant received;
        private Address from = new Address("", "");
        private final List<Address> to = new ArrayList<>();

        public String id() { return id; }
        public String subject() { return subject; }
        public String bodyPreview() { return bodyPreview; }
        public String bodyText() { return bodyText; }
        public String bodyHtml() { return bodyHtml; }
        public String internetMessageId() { return internetMessageId; }
        public boolean read() { return read; }
        public boolean flagged() { return flagged; }
        public boolean hasAttachments() { return hasAttachments; }
        public long size() { return size; }
        public Instant received() { return received; }
        public Address from() { return from; }
        public List<Address> to() { return List.copyOf(to); }
    }
}
