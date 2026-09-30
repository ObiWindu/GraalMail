package com.graalmail.mail

import com.graalmail.model.*
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Properties

class ImapSmtpClient {
    private data class Endpoints(val imap: String, val smtp: String)
    private fun endpoints(p: Provider) = when (p) {
        Provider.GMAIL -> Endpoints("imap.gmail.com", "smtp.gmail.com")
        Provider.MICROSOFT -> Endpoints("outlook.office365.com", "smtp.office365.com")
        Provider.YAHOO -> Endpoints("imap.mail.yahoo.com", "smtp.mail.yahoo.com")
    }

    fun fetchInbox(p: Provider, email: String, token: String, accountId: Long): List<MailMessage> {
        val ep = endpoints(p)
        val props = Properties().apply {
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.auth.mechanisms", "XOAUTH2")
        }
        val session = Session.getInstance(props)
        val store = session.getStore("imaps")
        store.connect(ep.imap, email, token)
        val folder = store.getFolder("INBOX")
        folder.open(Folder.READ_ONLY)
        val count = folder.messageCount
        val first = maxOf(1, count - 49)
        val messages = if (count == 0) emptyArray() else folder.getMessages(first, count)
        val result = messages.map { m ->
            val content = m.content
            MailMessage(
                id = "$accountId:${m.hashCode()}",
                accountId = accountId, folder = "INBOX",
                threadId = m.getHeader("Message-ID", null),
                from = m.from?.joinToString(", ") ?: "",
                to = m.getRecipients(Message.RecipientType.TO)?.joinToString(", ") ?: "",
                subject = m.subject ?: "",
                date = m.sentDate?.toInstant()?.toString() ?: "",
                bodyText = if (content is String) content else content.toString(),
                bodyHtml = if (content is String) content else "",
                read = m.isSet(Flags.Flag.SEEN),
                starred = m.isSet(Flags.Flag.FLAGGED),
                hasAttachments = false
            )
        }.reversed()
        folder.close(false); store.close()
        return result
    }

    fun send(p: Provider, email: String, token: String, to: String, subject: String, body: String) {
        val ep = endpoints(p)
        val props = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.auth.mechanisms", "XOAUTH2")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.starttls.required", "true")
            put("mail.smtp.port", "587")
        }
        val session = Session.getInstance(props)
        val msg = MimeMessage(session).apply {
            setFrom(InternetAddress(email))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
            setSubject(subject, "UTF-8")
            setText(body, "UTF-8")
        }
        Transport.send(msg, email, token)
    }
}
