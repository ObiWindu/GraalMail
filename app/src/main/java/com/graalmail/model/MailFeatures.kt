package com.graalmail.model

import java.util.Date

enum class MailFolderType { INBOX, SENT, DRAFTS, ARCHIVE, TRASH, SPAM, CUSTOM }
enum class OutgoingOperation { SEND, SAVE_DRAFT, MOVE, DELETE, MARK_READ, MARK_UNREAD, STAR, UNSTAR }

data class MailFolder(
    val id: String,
    val name: String,
    val type: MailFolderType,
    val unreadCount: Int = 0
)

data class MailAttachment(
    val id: String,
    val messageId: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val contentId: String? = null,
    val localPath: String? = null
)

data class Draft(
    val id: String,
    val accountId: Long,
    val to: List<String>,
    val cc: List<String>,
    val bcc: List<String>,
    val subject: String,
    val bodyHtml: String,
    val bodyText: String,
    val updatedAt: Long
)

data class PendingOperation(
    val id: Long = 0,
    val accountId: Long,
    val messageId: String?,
    val operation: OutgoingOperation,
    val payload: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    val lastError: String? = null
)
