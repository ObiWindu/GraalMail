package com.graalmail.mime

import jakarta.mail.BodyPart
import jakarta.mail.Message
import jakarta.mail.Multipart
import org.jsoup.Jsoup

data class ParsedMime(
    val text: String,
    val html: String,
    val attachments: List<ParsedAttachment>,
    val inlineParts: List<ParsedAttachment>
)
data class ParsedAttachment(
    val fileName: String,
    val mimeType: String,
    val contentId: String?,
    val bytes: ByteArray
)

object MimeMessageParser {
    fun parse(message: Message): ParsedMime {
        val attachments = mutableListOf<ParsedAttachment>()
        var text = ""
        var html = ""
        fun walk(value: Any) {
            when (value) {
                is String -> if (html.isEmpty()) text = value
                is Multipart -> for (i in 0 until value.count) {
                    val part = value.getBodyPart(i)
                    val disposition = part.disposition ?: ""
                    val ct = part.contentType.lowercase()
                    if (disposition.equals("attachment", true) ||
                        disposition.equals("inline", true) && !ct.startsWith("text/")) {
                        attachments += ParsedAttachment(
                            part.fileName ?: "attachment",
                            part.contentType.substringBefore(';'),
                            part.getHeader("Content-ID", null),
                            part.inputStream.readBytes()
                        )
                    } else {
                        val content = part.content
                        if (ct.startsWith("text/html")) html = content.toString()
                        else walk(content)
                    }
                }
            }
        }
        walk(message.content)
        if (html.isNotBlank() && text.isBlank()) text = Jsoup.parse(html).text()
        return ParsedMime(text, html, attachments.filter { it.contentId == null },
            attachments.filter { it.contentId != null })
    }
}
