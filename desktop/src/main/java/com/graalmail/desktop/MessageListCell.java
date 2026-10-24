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
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Renders a scannable message row for the desktop inbox. */
final class MessageListCell extends ListCell<Message> {

    private final boolean compact;

    MessageListCell(boolean compact) {
        this.compact = compact;
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
        Label sender = new Label(message.from().name().isBlank()
                ? message.from().email() : message.from().name());
        sender.getStyleClass().add("message-sender");
        if (message.unread()) sender.getStyleClass().add("unread");
        Label date = new Label(formatDate(message.date()));
        date.getStyleClass().add("message-date");
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox firstLine = new HBox(8, sender, spacer, date);
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label subject = new Label((message.flagged() ? "★  " : "")
                + (message.subject().isBlank() ? "(No subject)" : message.subject())
                + (message.hasAttachments() ? "  📎" : ""));
        subject.getStyleClass().add("message-subject");
        Label snippet = new Label(message.snippet());
        snippet.getStyleClass().add("message-snippet");
        snippet.setMaxWidth(Double.MAX_VALUE);
        snippet.setTextOverrun(OverrunStyle.ELLIPSIS);
        VBox content = new VBox(4, firstLine, subject, snippet);
        content.getStyleClass().add("message-cell-content");
        setPadding(compact ? new Insets(6, 9, 6, 9) : new Insets(11, 10, 11, 10));
        setGraphic(content);
        setText(null);
        getStyleClass().remove("unread-row");
        if (message.unread()) getStyleClass().add("unread-row");
    }

    private static String formatDate(Instant date) {
        if (date == null) return "";
        ZonedDateTime value = date.atZone(ZoneId.systemDefault());
        DateTimeFormatter formatter = value.toLocalDate().equals(java.time.LocalDate.now())
                ? DateTimeFormatter.ofPattern("HH:mm") : DateTimeFormatter.ofPattern("d MMM");
        return value.format(formatter);
    }
}
