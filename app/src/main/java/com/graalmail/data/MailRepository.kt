package com.graalmail.data

import android.content.Context
import com.graalmail.mail.ImapSmtpClient
import com.graalmail.model.*
import com.graalmail.security.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MailRepository(context: Context, private val db: AppDatabase) {
    private val tokens = TokenStore(context)
    private val transport = ImapSmtpClient()

    suspend fun accounts() = db.dao().accounts()
    suspend fun inbox(accountId: Long) =
        db.dao().messages(accountId, "INBOX").map { it.toModel() }

    suspend fun sync(account: AccountEntity): List<MailMessage> = withContext(Dispatchers.IO) {
        val token = tokens.access(account.id) ?: error("No OAuth token for ${account.email}")
        val result = transport.fetchInbox(Provider.valueOf(account.provider), account.email, token, account.id)
        db.dao().upsertMessages(result.map { it.toEntity() })
        result
    }

    suspend fun send(account: AccountEntity, to: String, subject: String, body: String) =
        withContext(Dispatchers.IO) {
            val token = tokens.access(account.id) ?: error("No OAuth token")
            transport.send(Provider.valueOf(account.provider), account.email, token, to, subject, body)
        }

    suspend fun markRead(id: String) {
        db.dao().message(id)?.let { db.dao().updateMessage(it.copy(read = true)) }
    }
    suspend fun archive(id: String) {
        db.dao().message(id)?.let { db.dao().updateMessage(it.copy(folder = "ARCHIVE")) }
    }

    private fun MessageEntity.toModel() = MailMessage(id,accountId,folder,threadId,fromAddress,toAddress,subject,date,bodyText,bodyHtml,read,starred,hasAttachments)
    private fun MailMessage.toEntity() = MessageEntity(id,accountId,folder,threadId,from,to,subject,date,bodyText,bodyHtml,read,starred,hasAttachments)
}
