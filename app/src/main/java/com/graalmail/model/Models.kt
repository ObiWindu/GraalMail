package com.graalmail.model

enum class Provider { GMAIL, MICROSOFT, YAHOO }

data class MailAccount(
    val id: Long, val email: String, val displayName: String, val provider: Provider
)

data class MailMessage(
    val id: String, val accountId: Long, val folder: String, val threadId: String?,
    val from: String, val to: String, val subject: String, val date: String,
    val bodyText: String, val bodyHtml: String, val read: Boolean, val starred: Boolean,
    val hasAttachments: Boolean
)
