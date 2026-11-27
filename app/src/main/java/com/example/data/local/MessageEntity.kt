package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val roomId: String,
    val senderUid: String,
    val messageType: String, // TEXT, IMAGE, VOICE, FILE_P2P
    val decryptedText: String,
    val mediaBase64: String? = null,
    val localFilePath: String? = null,
    val voiceDurationMs: Long = 0L,
    val replyToMessageId: String? = null,
    val replyPreviewText: String? = null,
    val status: String = "SENT", // SENT, DELIVERED, READ
    val reactionJson: String = "",
    val starred: Boolean = false,
    val deletedForEveryone: Boolean = false,
    val expiresAtMillis: Long = 0L,
    val createdAtMillis: Long = System.currentTimeMillis()
)
