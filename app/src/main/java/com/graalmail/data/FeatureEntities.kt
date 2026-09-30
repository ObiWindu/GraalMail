package com.graalmail.data

import androidx.room.*

@Entity(tableName = "folders", primaryKeys = ["accountId", "id"])
data class FolderEntity(
    val accountId: Long,
    val id: String,
    val name: String,
    val type: String,
    val unreadCount: Int
)

@Entity(tableName = "attachments", primaryKeys = ["id"])
data class AttachmentEntity(
    val id: String,
    val messageId: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val contentId: String?,
    val localPath: String?
)

@Entity(tableName = "pending_operations")
data class PendingOperationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val messageId: String?,
    val operation: String,
    val payload: String?,
    val createdAt: Long,
    val attempts: Int,
    val lastError: String?
)

@Dao
interface FeatureDao {
    @Query("SELECT * FROM folders WHERE accountId=:accountId ORDER BY name")
    suspend fun folders(accountId: Long): List<FolderEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolders(items: List<FolderEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAttachments(items: List<AttachmentEntity>)
    @Query("SELECT * FROM pending_operations ORDER BY createdAt")
    suspend fun pending(): List<PendingOperationEntity>
    @Insert
    suspend fun enqueue(item: PendingOperationEntity): Long
    @Delete
    suspend fun remove(item: PendingOperationEntity)
    @Update
    suspend fun update(item: PendingOperationEntity)
    @Query("SELECT * FROM messages WHERE accountId=:accountId AND (subject LIKE '%' || :query || '%' OR fromAddress LIKE '%' || :query || '%' OR bodyText LIKE '%' || :query || '%') ORDER BY date DESC")
    suspend fun search(accountId: Long, query: String): List<MessageEntity>
}
