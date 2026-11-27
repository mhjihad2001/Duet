package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE roomId = :roomId ORDER BY createdAtMillis ASC")
    fun observeMessages(roomId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE roomId = :roomId AND starred = 1 AND deletedForEveryone = 0 ORDER BY createdAtMillis DESC")
    fun observeStarredMessages(roomId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE roomId = :roomId AND deletedForEveryone = 0 AND decryptedText LIKE '%' || :query || '%' ORDER BY createdAtMillis DESC")
    fun searchMessages(roomId: String, query: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(messages: List<MessageEntity>)

    @Query("UPDATE messages SET starred = :starred WHERE id = :id")
    suspend fun updateStarred(id: String, starred: Boolean)

    @Query("UPDATE messages SET localFilePath = :localPath WHERE id = :id")
    suspend fun updateLocalFilePath(id: String, localPath: String)

    @Query("SELECT id FROM messages WHERE expiresAtMillis > 0 AND expiresAtMillis <= :nowMillis")
    suspend fun getExpiredMessageIds(nowMillis: Long): List<String>

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM messages WHERE roomId = :roomId")
    suspend fun clearRoom(roomId: String)
}
