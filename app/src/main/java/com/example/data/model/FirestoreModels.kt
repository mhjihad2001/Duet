package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val fcmToken: String? = null,
    val roomId: String? = null,
    val partnerUid: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CoupleRoom(
    @DocumentId val id: String = "",
    val creatorUid: String = "",
    val memberUids: List<String> = emptyList(),
    val inviteCodeHash: String = "",
    val disappearingSeconds: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class PresenceStatus(
    @DocumentId val id: String = "",
    val userId: String = "",
    val memberUids: List<String> = emptyList(),
    val displayName: String = "",
    val online: Boolean = false,
    val typing: Boolean = false,
    val recordingAudio: Boolean = false,
    val fcmToken: String? = null,
    @ServerTimestamp val lastSeenAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class EncryptedMessageDto(
    @DocumentId val id: String = "",
    val senderUid: String = "",
    val memberUids: List<String> = emptyList(),
    val messageType: String = "TEXT",
    val encryptedPayload: String = "",
    val replyToMessageId: String? = null,
    val encryptedReplyPreview: String? = null,
    val status: String = "SENT",
    val reactionJson: String? = null,
    val deletedForEveryone: Boolean = false,
    val expiresAtMillis: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CallSignalDto(
    @DocumentId val id: String = "",
    val callerUid: String = "",
    val memberUids: List<String> = emptyList(),
    val callType: String = "AUDIO",
    val status: String = "RINGING",
    val encryptedOffer: String? = null,
    val encryptedAnswer: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class IceCandidateDto(
    @DocumentId val id: String = "",
    val senderUid: String = "",
    val memberUids: List<String> = emptyList(),
    val encryptedCandidate: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)
