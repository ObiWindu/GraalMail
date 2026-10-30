/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWandu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import com.graalmail.core.domain.Address;
import com.graalmail.core.domain.Message;
import com.graalmail.core.text.HtmlSanitizer;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.time.ZoneId;

/** Builds the sanitized, readable HTML document shown in the desktop reader. */
final class MessageBodyRenderer {

    private MessageBodyRenderer() { }

    static String render(Message message, boolean allowRemoteImages, boolean dark) {
        // Palette matches styles/mail.css (:root and .dark-theme) so the reader
        // never flashes a foreign background in either theme.
        String text = dark ? "#FCFCFC" : "#232629";
        String background = dark ? "#2A2E32" : "#FCFCFC";
        String muted = dark ? "#95A5A6" : "#7F7F7F";
        String accent = dark ? "#3DAEE9" : "#3DAEE9";
        String rule = dark ? "#4D4D4D" : "#CFCFCF";
        StringBuilder html = new StringBuilder();
        html.append("<html><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>")
                .append("<style>body{font-family:system-ui,-apple-system,'Segoe UI',sans-serif;")
                .append("font-size:14.5px;line-height:1.7;color:").append(text)
                .append(";background:").append(background)
                .append(";margin:0 auto;padding:20px;max-width:100%;box-sizing:border-box;}")
                .append(".meta{color:").append(muted).append(";font-size:13px;line-height:1.6}")
                .append("a{color:").append(accent).append("}")
                .append("hr{border:0;border-top:1px solid ").append(rule).append(";margin:16px 0}")
                .append("blockquote{border-left:3px solid ").append(rule)
                .append(";margin:12px 0;padding:4px 0 4px 14px;color:").append(muted).append("}")
                .append("pre{white-space:pre-wrap;word-wrap:break-word;font:inherit;background:")
                .append(dark ? "#31363B" : "#F5F5F5").append(";border-radius:6px;padding:14px 16px}")
                .append("p{margin:0 0 12px}img{max-width:100%;height:auto}</style></head><body>");
        html.append("<p class='meta'><b>From</b> ").append(escape(formatAddress(message.from())))
                .append("<br>");
        appendAddresses(html, "To", message.to());
        appendAddresses(html, "Cc", message.cc());
        if (message.date() != null) {
            html.append("<b>Date</b> ").append(escape(DateTimeFormatter
                    .ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
                    .withZone(ZoneId.systemDefault()).format(message.date())));
        }
        html.append("</p><hr>");
        String bodyHtml = message.bodyHtml() == null ? "" : message.bodyHtml();
        String bodyText = message.bodyText() == null ? "" : message.bodyText();
        if (!bodyHtml.isBlank()) html.append(HtmlSanitizer.sanitize(bodyHtml, allowRemoteImages));
        else if (bodyText.isBlank()) html.append("<p class='meta'>This message has no content.</p>");
        else {
            String[] paragraphs = bodyText.split("\\r?\\n\\r?\\n");
            for (String paragraph : paragraphs) {
                String single = paragraph.replace('\r', ' ').replace('\n', ' ').trim();
                if (single.isEmpty()) {
                    continue;
                }
                html.append("<p>").append(escape(single)).append("</p>");
            }
        }
        return html.append("</body></html>").toString();
    }

    private static void appendAddresses(StringBuilder html, String label, java.util.List<Address> addresses) {
        if (addresses.isEmpty()) return;
        html.append("<b>").append(label).append("</b> ")
                .append(escape(addresses.stream().map(MessageBodyRenderer::formatAddress)
                        .reduce((a, b) -> a + ", " + b).orElse(""))).append("<br>");
    }

    private static String formatAddress(Address address) {
        return address.name().isBlank() ? address.email() : address.name() + " <" + address.email() + ">";
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
