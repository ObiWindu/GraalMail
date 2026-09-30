package com.graalmail.data

import android.content.Context
import androidx.room.*

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val email: String, val displayName: String, val provider: String
)

@Entity(tableName = "messages", indices = [Index("accountId"), Index("folder"), Index("threadId")])
data class MessageEntity(
    @PrimaryKey val id: String, val accountId: Long, val folder: String, val threadId: String?,
    val fromAddress: String, val toAddress: String, val subject: String, val date: String,
    val bodyText: String, val bodyHtml: String, val read: Boolean, val starred: Boolean,
    val hasAttachments: Boolean
)

@Dao
interface MailDao {
    @Query("SELECT * FROM messages WHERE accountId=:accountId AND folder=:folder ORDER BY date DESC")
    suspend fun messages(accountId: Long, folder: String): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE id=:id LIMIT 1")
    suspend fun message(id: String): MessageEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(items: List<MessageEntity>)
    @Update suspend fun updateMessage(item: MessageEntity)
    @Query("SELECT * FROM accounts ORDER BY id")
    suspend fun accounts(): List<AccountEntity>
    @Insert suspend fun insertAccount(item: AccountEntity): Long
}

@Database(entities=[AccountEntity::class, MessageEntity::class], version=1, exportSchema=false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): MailDao
    companion object {
        fun create(context: Context) =
            Room.databaseBuilder(context, AppDatabase::class.java, "graal-mail.db").build()
    }
}
