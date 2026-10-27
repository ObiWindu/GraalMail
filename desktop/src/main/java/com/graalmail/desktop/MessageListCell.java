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
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

/** Renders a scannable message row for the desktop inbox. */
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
        Label sender = new Label(message.from().name().isBlank()
                ? message.from().email() : message.from().name());
        sender.getStyleClass().add("message-sender");
        if (message.unread()) sender.getStyleClass().add("unread");
        Label date = new Label(formatDate(message.date()));
        date.getStyleClass().add("message-date");
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        StackPane readStatus = new StackPane();
        readStatus.getStyleClass().add("message-read-status");
        if (message.unread()) {
            Circle unreadDot = new Circle(3 * scale);
            unreadDot.getStyleClass().add("message-unread-dot");
            readStatus.getChildren().add(unreadDot);
        }
        HBox firstLine = new HBox(5 * scale, readStatus, sender, spacer, date);
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label subject = new Label(message.subject().isBlank() ? "(No subject)" : message.subject());
        subject.getStyleClass().add("message-subject");
        if (message.unread()) subject.getStyleClass().add("unread");
        subject.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(subject, Priority.ALWAYS);
        HBox subjectLine = new HBox(subject);
        subjectLine.setMaxWidth(Double.MAX_VALUE);
        HBox metaLine = new HBox(6 * scale);
        metaLine.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        metaLine.setMaxWidth(Double.MAX_VALUE);
        if (message.hasAttachments()) {
            Node attachment = ThunderbirdIcons.icon("attachment", "message-attachment-icon");
            attachment.setAccessibleText("Attachment");
            metaLine.getChildren().add(attachment);
        }
        if (message.flagged()) {
            Node star = ThunderbirdIcons.icon("star", "message-star-icon");
            star.setAccessibleText("Starred");
            metaLine.getChildren().add(star);
        }
        subjectLine.setFillHeight(true);
        metaLine.setAccessibleText((message.flagged() ? "Starred. " : "")
                + (message.hasAttachments() ? "Has attachment." : ""));
        VBox content = new VBox(1 * scale, firstLine, subjectLine, metaLine);
        content.getStyleClass().add("message-cell-content");
        setPadding(compact ? new Insets(3 * scale, 8 * scale, 3 * scale, 8 * scale)
                : new Insets(6 * scale, 8 * scale, 6 * scale, 8 * scale));
        setGraphic(content);
        setText(null);
        setAccessibleText(accessibleSummary(message));
        getStyleClass().remove("unread-row");
        if (message.unread()) getStyleClass().add("unread-row");
    }

    private static String formatDate(Instant date) {
        if (date == null) return "";
        ZonedDateTime value = date.atZone(ZoneId.systemDefault());
        DateTimeFormatter formatter = value.toLocalDate().equals(java.time.LocalDate.now())
                ? DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
                : DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault());
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
