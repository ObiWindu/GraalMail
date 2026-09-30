package com.graalmail.ui

import com.graalmail.model.MailMessage

data class Conversation(
    val threadId: String,
    val subject: String,
    val messages: List<MailMessage>
)

fun List<MailMessage>.toConversations(): List<Conversation> =
    groupBy { it.threadId ?: it.id }
        .values
        .map { items -> Conversation(items.first().threadId ?: items.first().id, items.first().subject, items.sortedBy { it.date }) }
        .sortedByDescending { it.messages.lastOrNull()?.date ?: "" }
