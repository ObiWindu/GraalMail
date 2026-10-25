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
        String text = dark ? "#e5e9f0" : "#202633";
        String background = dark ? "#202631" : "#ffffff";
        String muted = dark ? "#aab4c4" : "#667085";
        StringBuilder html = new StringBuilder();
        html.append("<html><head><meta charset='UTF-8'><style>body{font-family:system-ui,sans-serif;")
                .append("font-size:14px;line-height:1.65;color:").append(text)
                .append(";background:").append(background)
                .append(";margin:28px;max-width:900px;}h1{font-size:23px;line-height:1.3;margin:0 0 18px}")
                .append(".meta{color:").append(muted).append(";font-size:13px;}")
                .append("hr{border:0;border-top:1px solid #e4e7ec;margin:20px 0}")
                .append("pre{white-space:pre-wrap;word-wrap:break-word;font:inherit}</style></head><body>");
        html.append("<h1>").append(escape(message.subject().isBlank() ? "(No subject)" : message.subject()))
                .append("</h1><p class='meta'><b>From</b> ").append(escape(formatAddress(message.from())))
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
        else html.append("<pre>").append(escape(bodyText)).append("</pre>");
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
