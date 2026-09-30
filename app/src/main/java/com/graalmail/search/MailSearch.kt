package com.graalmail.search

import com.graalmail.data.FeatureDao
import com.graalmail.model.MailMessage

class MailSearch(private val dao: FeatureDao) {
    suspend fun local(accountId: Long, query: String): List<MailMessage> =
        dao.search(accountId, query).map {
            MailMessage(it.id,it.accountId,it.folder,it.threadId,it.fromAddress,it.toAddress,
                it.subject,it.date,it.bodyText,it.bodyHtml,it.read,it.starred,it.hasAttachments)
        }
}
