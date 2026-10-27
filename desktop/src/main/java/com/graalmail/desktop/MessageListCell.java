/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWandu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import com.graalmail.core.domain.Message;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Renders a modern Proton-style message row: avatar, sender, subject, snippet. */
final class MessageListCell extends ListCell<Message> {

    private final boolean compact;
    private final double scale;

    MessageListCell(boolean compact, double scale) {
        this.compact = compact;
        this.scale = scale;
    }

    @Override
    protected void updateItem(Message message, boolean empty) {
        super.updateItem(message, empty);
        if (empty || message == null) {
            setText(null);
            setGraphic(null);
            getStyleClass().remove("unread-row");
            return;
        }
        String senderName = message.from().name().isBlank()
                ? message.from().email() : message.from().name();
        StackPane avatar = ProtonAvatar.avatar(
                message.from().name(), message.from().email(), (compact ? 30 : 36) * scale);

        Label sender = new Label(senderName);
        sender.getStyleClass().add("message-sender");
        if (message.unread()) sender.getStyleClass().add("unread");
        sender.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(sender, Priority.ALWAYS);

        Label date = new Label(formatDate(message.date()));
        date.getStyleClass().add("message-date");

        HBox topLine = new HBox(6 * scale, sender, date);
        topLine.setAlignment(Pos.CENTER_LEFT);

        Label subject = new Label(message.subject().isBlank() ? "(No subject)" : message.subject());
        subject.getStyleClass().add("message-subject");
        if (message.unread()) subject.getStyleClass().add("unread");

        String snippet = snippet(message);
        Label snippetLabel = new Label(snippet);
        snippetLabel.getStyleClass().add("message-snippet");
        snippetLabel.setMaxWidth(Double.MAX_VALUE);

        HBox metaLine = new HBox(6 * scale);
        metaLine.setAlignment(Pos.CENTER_LEFT);
        if (message.unread()) {
            Label unreadPill = new Label("Unread");
            unreadPill.getStyleClass().add("message-unread-pill");
            metaLine.getChildren().add(unreadPill);
        }
        if (message.flagged()) {
            Node star = ThunderbirdIcons.icon("star", "message-star-icon");
            star.setAccessibleText("Starred");
            metaLine.getChildren().add(star);
        }
        if (message.hasAttachments()) {
            Node attachment = ThunderbirdIcons.icon("attachment", "message-attachment-icon");
            attachment.setAccessibleText("Attachment");
            metaLine.getChildren().add(attachment);
        }
        if (message.attachments().size() > 1) {
            Label count = new Label("×" + message.attachments().size());
            count.getStyleClass().add("message-indicator");
            metaLine.getChildren().add(count);
        }
        metaLine.setAccessibleText((message.flagged() ? "Starred. " : "")
                + (message.hasAttachments() ? "Has attachment." : ""));
        metaLine.setVisible(!metaLine.getChildren().isEmpty());
        metaLine.setManaged(!metaLine.getChildren().isEmpty());

        VBox text = new VBox((compact ? 1 : 2) * scale, topLine, subject);
        if (!snippet.isBlank()) text.getChildren().add(snippetLabel);
        if (metaLine.isVisible()) text.getChildren().add(metaLine);
        text.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox row = new HBox(10 * scale, avatar, text);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("message-cell-content");
        row.setMaxWidth(Double.MAX_VALUE);

        setPadding(compact ? new Insets(6 * scale, 10 * scale, 6 * scale, 10 * scale)
                : new Insets(9 * scale, 12 * scale, 9 * scale, 12 * scale));
        setGraphic(row);
        setText(null);
        setAccessibleText(accessibleSummary(message));
        getStyleClass().remove("unread-row");
        if (message.unread()) getStyleClass().add("unread-row");
    }

    private static String snippet(Message message) {
        String body = message.bodyText() == null ? "" : message.bodyText().strip();
        if (body.isEmpty() && message.bodyHtml() != null) {
            body = message.bodyHtml().replaceAll("(?is)<[^>]*>", " ").replaceAll("\\s+", " ").strip();
        }
        // Skip a leading quote header ("On ... wrote:") for a cleaner preview.
        if (body.startsWith("On ") && body.contains("wrote:")) {
            int end = body.indexOf("wrote:") + 6;
            if (end < body.length()) body = body.substring(end).strip();
        }
        body = body.replaceAll("\\s+", " ");
        if (body.length() > 120) body = body.substring(0, 117).stripTrailing() + "…";
        return body;
    }

    private static String formatDate(Instant date) {
        if (date == null) return "";
        ZonedDateTime value = date.atZone(ZoneId.systemDefault());
        java.time.LocalDate today = java.time.LocalDate.now();
        DateTimeFormatter formatter;
        if (value.toLocalDate().equals(today)) {
            formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault());
        } else if (value.toLocalDate().getYear() == today.getYear()) {
            formatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault());
        } else {
            formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault());
        }
        return value.format(formatter);
    }

    private static String accessibleSummary(Message message) {
        return (message.unread() ? "Unread. " : "")
                + (message.flagged() ? "Starred. " : "")
                + (message.from().name().isBlank() ? message.from().email() : message.from().name())
                + ". " + (message.subject().isBlank() ? "No subject" : message.subject())
                + (message.hasAttachments() ? ". Has attachment." : "");
    }
}
