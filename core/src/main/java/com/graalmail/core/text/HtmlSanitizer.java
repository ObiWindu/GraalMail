/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.text;

import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Safelist;

/**
 * Sanitizes untrusted HTML mail. Scripts, event handlers, external references and
 * dangerous URL schemes are removed. Remote images are additionally neutralised
 * unless explicitly allowed.
 */
public final class HtmlSanitizer {

    private static final Safelist SAFE = Safelist.basic()
            .addTags("table", "thead", "tbody", "tfoot", "tr", "th", "td", "caption",
                    "colgroup", "col", "div", "span", "p", "br", "hr", "pre", "blockquote",
                    "center", "font", "small", "big", "u", "s", "strike", "sub", "sup",
                    "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "dl", "dt", "dd",
                    "abbr", "cite", "q", "figure", "figcaption", "picture", "source")
            .addAttributes("a", "href", "title", "name")
            .addAttributes("img", "src", "alt", "title", "width", "height")
            .addAttributes("td", "colspan", "rowspan", "align", "valign", "bgcolor")
            .addAttributes("th", "colspan", "rowspan", "align", "valign", "bgcolor")
            .addAttributes("font", "color", "face", "size")
            .addAttributes("table", "border", "cellpadding", "cellspacing", "width", "align")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addProtocols("img", "src", "http", "https", "cid", "data");

    private HtmlSanitizer() {
    }

    /** Sanitizes HTML and strips remote images so nothing is fetched from the network. */
    public static String sanitize(String html) {
        return sanitize(html, false);
    }

    public static String sanitize(String html, boolean allowRemoteImages) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document dirty = Jsoup.parseBodyFragment(html);
        Document clean = Jsoup.parseBodyFragment(Jsoup.clean(dirty.body().html(), "", SAFE, new Document.OutputSettings()
                .prettyPrint(false)));
        if (!allowRemoteImages) {
            for (Element img : clean.select("img[src]")) {
                String src = img.attr("src");
                if (src.startsWith("cid:")) {
                    continue;
                }
                img.attr("src", "about:blank");
                img.attr("data-remote-src", src);
            }
        }
        stripEventAttributes(clean);
        return clean.body().html();
    }

    /** Converts HTML to readable plain text, used for previews and local indexing. */
    public static String toPlainText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document doc = Jsoup.parse(html);
        doc.select("br").append("\\n");
        doc.select("p,div,tr,li,h1,h2,h3,h4,h5,h6").append("\\n\\n");
        String text = doc.text().replace("\\n", "\n");
        return text.replaceAll("\n{3,}", "\n\n").trim();
    }

    /** Removes any element not on the allow list (used for aggressive mode). */
    public static String stripDangerousTags(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document doc = Jsoup.parseBodyFragment(html);
        doc.select("script, style, iframe, object, embed, form, input, link, meta, base").remove();
        List<Node> nodes = doc.body().childNodes();
        return Jsoup.clean(doc.body().html(), "", SAFE);
    }

    private static void stripEventAttributes(Document doc) {
        for (Element element : doc.getAllElements()) {
            element.attributes().asList().forEach(attr -> {
                String key = attr.getKey();
                if (key.startsWith("on") || key.equals("style") && attr.getValue().toLowerCase().contains("expression")) {
                    element.removeAttr(key);
                }
            });
        }
    }

    static {
        // Touch TextNode to keep jsoup's node API referenced for future use.
        TextNode.class.getName();
    }
}
