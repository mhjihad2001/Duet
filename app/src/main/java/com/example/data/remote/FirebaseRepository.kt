package com.example.data.remote

import android.content.Context
import com.example.R
import com.example.data.crypto.CryptoManager
import com.example.data.local.MessageDao
import com.example.data.local.MessageEntity
import com.example.data.model.CallSignalDto
import com.example.data.model.CoupleRoom
import com.example.data.model.EncryptedMessageDto
import com.example.data.model.IceCandidateDto
import com.example.data.model.PresenceStatus
import com.example.data.model.UserProfile
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

class FirebaseRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val cryptoManager: CryptoManager,
    private val messageDao: MessageDao,
    private val pushRelayClient: PushRelayClient
) {
    constructor(
        context: Context,
        auth: FirebaseAuth,
        cryptoManager: CryptoManager,
        messageDao: MessageDao,
        pushRelayClient: PushRelayClient
    ) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        auth = auth,
        cryptoManager = cryptoManager,
        messageDao = messageDao,
        pushRelayClient = pushRelayClient
    )

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    suspend fun getUserProfile(uid: String): Result<UserProfile?> {
        val path = "users/$uid"
        return try {
            val snap = db.collection("users").document(uid).get().await()
            val profile = snap.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            Result.success(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, path)
            Result.failure(e)
        }
    }

    suspend fun upsertUserProfile(
        displayName: String,
        fcmToken: String? = null,
        roomId: String? = null,
        partnerUid: String? = null
    ): Result<Unit> {
        var path = "users/unknown"
        return try {
            val uid = requireUserId()
            path = "users/$uid"
            val docRef = db.collection("users").document(uid)
            val existing = docRef.get().await()
            val cleanName = displayName.trim().ifBlank { "Duet User" }.take(80)

            if (!existing.exists()) {
                val payload = mapOf(
                    "userId" to uid,
                    "displayName" to cleanName,
                    "fcmToken" to fcmToken,
                    "roomId" to roomId,
                    "partnerUid" to partnerUid,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null }
                docRef.set(payload).await()
            } else {
                val updateMap = mutableMapOf<String, Any>(
                    "displayName" to cleanName,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (!fcmToken.isNullOrBlank()) updateMap["fcmToken"] = fcmToken
                if (!roomId.isNullOrBlank()) updateMap["roomId"] = roomId
                if (!partnerUid.isNullOrBlank()) updateMap["partnerUid"] = partnerUid
                docRef.update(updateMap).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun createCoupleRoom(displayName: String): Result<CoupleRoom> {
        var path = "rooms"
        return try {
            val uid = requireUserId()
            val roomId = generateRoomCode()
            path = "rooms/$roomId"
            val inviteHash = cryptoManager.hashInviteCode(roomId)

            val payload = mapOf(
                "creatorUid" to uid,
                "memberUids" to listOf(uid),
                "inviteCodeHash" to inviteHash,
                "disappearingSeconds" to 0L,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.collection("rooms").document(roomId).set(payload).await()
            upsertUserProfile(displayName = displayName, fcmToken = cryptoManager.getLocalFcmToken(), roomId = roomId)
            updatePresence(
                roomId = roomId,
                memberUids = listOf(uid),
                displayName = displayName,
                online = true,
                typing = false,
                recordingAudio = false
            )

            val createdSnap = db.collection("rooms").document(roomId).get().await()
            val room = createdSnap.toObject(CoupleRoom::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: CoupleRoom(id = roomId, creatorUid = uid, memberUids = listOf(uid), inviteCodeHash = inviteHash)
            Result.success(room)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun joinCoupleRoom(rawRoomId: String, displayName: String): Result<CoupleRoom> {
        val roomId = rawRoomId.trim().uppercase()
        val path = "rooms/$roomId"
        return try {
            val uid = requireUserId()
            require(roomId.matches(Regex("^[a-zA-Z0-9_\\-]{4,64}$"))) { "Invalid Vault Room Code" }
            val docRef = db.collection("rooms").document(roomId)
            val snap = docRef.get().await()
            if (!snap.exists()) {
                return Result.failure(IllegalArgumentException("Couple vault not found. Check the Room Code."))
            }
            val room = snap.toObject(CoupleRoom::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: return Result.failure(IllegalStateException("Malformed room data"))

            val members = room.memberUids
            val finalMembers = when {
                uid in members -> members
                members.size == 1 -> listOf(members[0], uid)
                else -> return Result.failure(SecurityException("This vault already has 2 paired members."))
            }

            if (uid !in members) {
                docRef.update(
                    mapOf(
                        "memberUids" to finalMembers,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }

            val partnerUid = finalMembers.firstOrNull { it != uid }
            cryptoManager.setPartnerUid(partnerUid)
            upsertUserProfile(
                displayName = displayName,
                fcmToken = cryptoManager.getLocalFcmToken(),
                roomId = roomId,
                partnerUid = partnerUid
            )
            updatePresence(
                roomId = roomId,
                memberUids = finalMembers,
                displayName = displayName,
                online = true,
                typing = false,
                recordingAudio = false
            )

            Result.success(room.copy(memberUids = finalMembers))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    fun observeMyRoom(myUid: String): Flow<CoupleRoom?> = flow {
        val path = "rooms"
        emitAll(
            db.collection("rooms")
                .whereArrayContains("memberUids", myUid)
                .snapshots()
                .map { snapshot ->
                    snapshot.documents.firstOrNull()
                        ?.toObject(CoupleRoom::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == myUid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observePartnerPresence(roomId: String, myUid: String): Flow<PresenceStatus?> = flow {
        val path = "rooms/$roomId/presence"
        emitAll(
            db.collection("rooms").document(roomId).collection("presence")
                .whereArrayContains("memberUids", myUid)
                .snapshots()
                .map { snapshot ->
                    val list = snapshot.toObjects(
                        PresenceStatus::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                    list.firstOrNull { it.userId != myUid }?.also { partner ->
                        cryptoManager.setPartnerUid(partner.userId)
                        cryptoManager.setPartnerName(partner.displayName)
                        if (!partner.fcmToken.isNullOrBlank()) {
                            cryptoManager.setPartnerFcmToken(partner.fcmToken)
                        }
                    }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == myUid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun updatePresence(
        roomId: String,
        memberUids: List<String>,
        displayName: String,
        online: Boolean,
        typing: Boolean,
        recordingAudio: Boolean
    ): Result<Unit> {
        var path = "rooms/$roomId/presence"
        return try {
            val uid = requireUserId()
            path = "rooms/$roomId/presence/$uid"
            val validMembers = if (uid in memberUids) memberUids.distinct().take(2) else listOf(uid)
            val docRef = db.collection("rooms").document(roomId)
                .collection("presence").document(uid)
            val snap = docRef.get().await()
            val fcmToken = cryptoManager.getLocalFcmToken()
            val cleanName = displayName.trim().ifBlank { "Partner" }.take(80)

            if (!snap.exists()) {
                val payload = mapOf(
                    "userId" to uid,
                    "memberUids" to validMembers,
                    "displayName" to cleanName,
                    "online" to online,
                    "typing" to typing,
                    "recordingAudio" to recordingAudio,
                    "fcmToken" to fcmToken,
                    "lastSeenAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null }
                docRef.set(payload).await()
            } else {
                val updateMap = mutableMapOf<String, Any>(
                    "memberUids" to validMembers,
                    "displayName" to cleanName,
                    "online" to online,
                    "typing" to typing,
                    "recordingAudio" to recordingAudio,
                    "lastSeenAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (!fcmToken.isNullOrBlank()) {
                    updateMap["fcmToken"] = fcmToken
                }
                docRef.update(updateMap).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    /**
     * Subscribes to encrypted messages in Firestore, decrypts via CryptoManager,
     * caches into Room DB, marks incoming as DELIVERED, and purges expired messages.
     */
    fun syncRemoteMessages(roomId: String, myUid: String): Flow<Unit> = flow {
        val path = "rooms/$roomId/messages"
        emitAll(
            db.collection("rooms").document(roomId).collection("messages")
                .whereArrayContains("memberUids", myUid)
                .snapshots()
                .onEach { snapshot ->
                    val now = System.currentTimeMillis()
                    val dtos = snapshot.toObjects(
                        EncryptedMessageDto::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                    val entities = mutableListOf<MessageEntity>()
                    for (dto in dtos) {
                        if (dto.expiresAtMillis > 0L && dto.expiresAtMillis <= now) {
                            repoScope.launch {
                                deleteExpiredRemoteMessage(roomId, dto.id)
                                messageDao.deleteById(dto.id)
                            }
                            continue
                        }

                        val existingLocal = messageDao.getMessageById(dto.id)
                        val entity = decryptDtoToEntity(roomId, dto, existingLocal?.starred == true, existingLocal?.localFilePath)
                        entities.add(entity)

                        if (dto.senderUid != myUid && dto.status == "SENT") {
                            repoScope.launch {
                                updateMessageStatus(roomId, dto.id, "DELIVERED")
                            }
                        }
                    }
                    if (entities.isNotEmpty()) {
                        messageDao.upsertMessages(entities)
                    }
                }
                .map { Unit }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == myUid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeLocalMessages(roomId: String): Flow<List<MessageEntity>> =
        messageDao.observeMessages(roomId)

    fun observeStarredMessages(roomId: String): Flow<List<MessageEntity>> =
        messageDao.observeStarredMessages(roomId)

    fun searchLocalMessages(roomId: String, query: String): Flow<List<MessageEntity>> =
        messageDao.searchMessages(roomId, query)

    suspend fun toggleStarred(messageId: String, starred: Boolean) {
        messageDao.updateStarred(messageId, starred)
    }

    suspend fun attachLocalFilePath(messageId: String, path: String) {
        messageDao.updateLocalFilePath(messageId, path)
    }

    suspend fun sendEncryptedMessage(
        roomId: String,
        memberUids: List<String>,
        messageType: String,
        plaintext: String,
        mediaBase64: String? = null,
        voiceDurationMs: Long = 0L,
        replyToMessageId: String? = null,
        replyPreviewText: String? = null,
        disappearingSeconds: Long = 0L,
        senderName: String = "Partner"
    ): Result<String> {
        val messageId = "msg_${UUID.randomUUID().toString().replace("-", "")}"
        val path = "rooms/$roomId/messages/$messageId"
        return try {
            val uid = requireUserId()
            val validMembers = if (uid in memberUids) memberUids.distinct().take(2) else listOf(uid)
            val now = System.currentTimeMillis()
            val expiresAt = if (disappearingSeconds > 0L) now + (disappearingSeconds * 1000L) else 0L

            val innerJson = JSONObject().apply {
                put("text", plaintext)
                if (!mediaBase64.isNullOrBlank()) put("mediaB64", mediaBase64)
                if (voiceDurationMs > 0L) put("voiceDurationMs", voiceDurationMs)
            }.toString()

            val encryptedPayload = cryptoManager.encryptString(innerJson)
            val encryptedReply = replyPreviewText?.take(120)?.let { cryptoManager.encryptString(it) }

            // Cache immediately in Room DB so UI updates with zero latency
            val localEntity = MessageEntity(
                id = messageId,
                roomId = roomId,
                senderUid = uid,
                messageType = messageType,
                decryptedText = plaintext,
                mediaBase64 = mediaBase64,
                voiceDurationMs = voiceDurationMs,
                replyToMessageId = replyToMessageId,
                replyPreviewText = replyPreviewText,
                status = "SENT",
                reactionJson = "",
                starred = false,
                deletedForEveryone = false,
                expiresAtMillis = expiresAt,
                createdAtMillis = now
            )
            messageDao.upsertMessage(localEntity)

            val payload = mapOf(
                "senderUid" to uid,
                "memberUids" to validMembers,
                "messageType" to messageType,
                "encryptedPayload" to encryptedPayload,
                "replyToMessageId" to replyToMessageId,
                "encryptedReplyPreview" to encryptedReply,
                "status" to "SENT",
                "deletedForEveryone" to false,
                "expiresAtMillis" to expiresAt,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null }

            db.collection("rooms").document(roomId)
                .collection("messages").document(messageId)
                .set(payload)
                .await()

            // Trigger Spark-plan Push Relay webhook with AES-GCM encrypted FCM payload
            val pushJson = JSONObject().apply {
                put("roomId", roomId)
                put("messageId", messageId)
                put("senderUid", uid)
                put("senderName", senderName)
                put("messageType", messageType)
                put("text", if (messageType == "TEXT") plaintext else "Sent $messageType")
                put("expiresAtMillis", expiresAt)
                put("createdAtMillis", now)
            }
            pushRelayClient.sendPushAsync(
                targetToken = cryptoManager.getPartnerFcmToken(),
                type = "MESSAGE",
                payloadJson = pushJson
            )

            Result.success(messageId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun markMessagesAsRead(roomId: String, myUid: String, messages: List<MessageEntity>) {
        val unreadFromPartner = messages.filter { it.senderUid != myUid && it.status != "READ" }
        for (msg in unreadFromPartner) {
            updateMessageStatus(roomId, msg.id, "READ")
        }
    }

    suspend fun updateMessageStatus(roomId: String, messageId: String, status: String): Result<Unit> {
        val path = "rooms/$roomId/messages/$messageId"
        return try {
            requireUserId()
            db.collection("rooms").document(roomId)
                .collection("messages").document(messageId)
                .update(
                    mapOf(
                        "status" to status,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun toggleReaction(roomId: String, message: MessageEntity, myUid: String, emoji: String): Result<Unit> {
        val path = "rooms/$roomId/messages/${message.id}"
        return try {
            requireUserId()
            val map = parseReactions(message.reactionJson).toMutableMap()
            if (map[myUid] == emoji) {
                map.remove(myUid)
            } else {
                map[myUid] = emoji
            }
            val rawJson = JSONObject(map as Map<*, *>).toString()
            val encryptedReactions = cryptoManager.encryptString(rawJson)

            messageDao.upsertMessage(message.copy(reactionJson = rawJson))
            db.collection("rooms").document(roomId)
                .collection("messages").document(message.id)
                .update(
                    mapOf(
                        "reactionJson" to encryptedReactions,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteForEveryone(roomId: String, message: MessageEntity): Result<Unit> {
        val path = "rooms/$roomId/messages/${message.id}"
        return try {
            val uid = requireUserId()
            check(message.senderUid == uid) { "Only sender can delete for everyone" }
            val tombstone = cryptoManager.encryptString(JSONObject().apply {
                put("text", "This message was deleted")
            }.toString())
            messageDao.upsertMessage(
                message.copy(
                    decryptedText = "This message was deleted",
                    mediaBase64 = null,
                    deletedForEveryone = true
                )
            )
            db.collection("rooms").document(roomId)
                .collection("messages").document(message.id)
                .update(
                    mapOf(
                        "encryptedPayload" to tombstone,
                        "deletedForEveryone" to true,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateDisappearingTimer(roomId: String, seconds: Long): Result<Unit> {
        val path = "rooms/$roomId"
        return try {
            requireUserId()
            require(seconds in listOf(0L, 60L, 3600L, 86400L)) { "Unsupported timer value" }
            cryptoManager.setDisappearingSeconds(seconds)
            db.collection("rooms").document(roomId)
                .update(
                    mapOf(
                        "disappearingSeconds" to seconds,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun purgeExpiredMessages(roomId: String) {
        val now = System.currentTimeMillis()
        val expiredIds = messageDao.getExpiredMessageIds(now)
        for (id in expiredIds) {
            deleteExpiredRemoteMessage(roomId, id)
            messageDao.deleteById(id)
        }
    }

    private suspend fun deleteExpiredRemoteMessage(roomId: String, messageId: String) {
        try {
            db.collection("rooms").document(roomId)
                .collection("messages").document(messageId)
                .delete().await()
        } catch (_: Exception) {
        }
    }

    // --- Encrypted WebRTC Call Signaling ---

    suspend fun createCallOffer(
        roomId: String,
        memberUids: List<String>,
        callType: String,
        sdpOffer: String,
        callerName: String
    ): Result<String> {
        val callId = "call_${UUID.randomUUID().toString().replace("-", "")}"
        val path = "rooms/$roomId/calls/$callId"
        return try {
            val uid = requireUserId()
            val validMembers = if (uid in memberUids) memberUids.distinct().take(2) else listOf(uid)
            val encryptedOffer = cryptoManager.encryptString(sdpOffer)
            val payload = mapOf(
                "callerUid" to uid,
                "memberUids" to validMembers,
                "callType" to callType,
                "status" to "RINGING",
                "encryptedOffer" to encryptedOffer,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.collection("rooms").document(roomId)
                .collection("calls").document(callId)
                .set(payload).await()

            val pushJson = JSONObject().apply {
                put("roomId", roomId)
                put("callId", callId)
                put("callerUid", uid)
                put("callerName", callerName)
                put("callType", callType)
            }
            pushRelayClient.sendPushAsync(
                targetToken = cryptoManager.getPartnerFcmToken(),
                type = "CALL",
                payloadJson = pushJson
            )

            Result.success(callId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun answerCall(roomId: String, callId: String, sdpAnswer: String): Result<Unit> {
        val path = "rooms/$roomId/calls/$callId"
        return try {
            requireUserId()
            val encryptedAnswer = cryptoManager.encryptString(sdpAnswer)
            db.collection("rooms").document(roomId)
                .collection("calls").document(callId)
                .update(
                    mapOf(
                        "status" to "ACTIVE",
                        "encryptedAnswer" to encryptedAnswer,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateCallStatus(roomId: String, callId: String, status: String): Result<Unit> {
        val path = "rooms/$roomId/calls/$callId"
        return try {
            requireUserId()
            db.collection("rooms").document(roomId)
                .collection("calls").document(callId)
                .update(
                    mapOf(
                        "status" to status,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun sendIceCandidate(
        roomId: String,
        callId: String,
        memberUids: List<String>,
        candidateJson: String
    ): Result<Unit> {
        val candidateId = "ice_${UUID.randomUUID().toString().replace("-", "")}"
        val path = "rooms/$roomId/calls/$callId/candidates/$candidateId"
        return try {
            val uid = requireUserId()
            val validMembers = if (uid in memberUids) memberUids.distinct().take(2) else listOf(uid)
            val encryptedCandidate = cryptoManager.encryptString(candidateJson)
            val payload = mapOf(
                "senderUid" to uid,
                "memberUids" to validMembers,
                "encryptedCandidate" to encryptedCandidate,
                "createdAt" to FieldValue.serverTimestamp()
            )
            db.collection("rooms").document(roomId)
                .collection("calls").document(callId)
                .collection("candidates").document(candidateId)
                .set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    fun observeCalls(roomId: String, myUid: String): Flow<List<CallSignalDto>> = flow {
        val path = "rooms/$roomId/calls"
        emitAll(
            db.collection("rooms").document(roomId).collection("calls")
                .whereArrayContains("memberUids", myUid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        CallSignalDto::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    throw error
                }
        )
    }

    fun observeIceCandidates(roomId: String, callId: String, myUid: String): Flow<List<IceCandidateDto>> = flow {
        emitAll(
            db.collection("rooms").document(roomId)
                .collection("calls").document(callId)
                .collection("candidates")
                .whereArrayContains("memberUids", myUid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        IceCandidateDto::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    throw error
                }
        )
    }

    private fun decryptDtoToEntity(
        roomId: String,
        dto: EncryptedMessageDto,
        preserveStarred: Boolean,
        preserveLocalPath: String?
    ): MessageEntity {
        var text = "🔒 Encrypted message"
        var mediaB64: String? = null
        var voiceDur = 0L

        if (dto.deletedForEveryone) {
            text = "This message was deleted"
        } else {
            try {
                val decryptedJson = cryptoManager.decryptString(dto.encryptedPayload)
                val obj = JSONObject(decryptedJson)
                text = obj.optString("text", "")
                mediaB64 = obj.optString("mediaB64", "").ifBlank { null }
                voiceDur = obj.optLong("voiceDurationMs", 0L)
            } catch (_: Exception) {
                text = "🔒 Unable to decrypt (check Secret Passphrase)"
            }
        }

        val replyPreview = dto.encryptedReplyPreview?.let {
            try {
                cryptoManager.decryptString(it)
            } catch (_: Exception) {
                "Encrypted reply"
            }
        }

        val decryptedReactions = dto.reactionJson?.let {
            try {
                cryptoManager.decryptString(it)
            } catch (_: Exception) {
                ""
            }
        } ?: ""

        val createdMs = (dto.createdAt ?: Timestamp.now()).toDate().time

        return MessageEntity(
            id = dto.id,
            roomId = roomId,
            senderUid = dto.senderUid,
            messageType = dto.messageType,
            decryptedText = text,
            mediaBase64 = mediaB64,
            localFilePath = preserveLocalPath,
            voiceDurationMs = voiceDur,
            replyToMessageId = dto.replyToMessageId,
            replyPreviewText = replyPreview,
            status = dto.status,
            reactionJson = decryptedReactions,
            starred = preserveStarred,
            deletedForEveryone = dto.deletedForEveryone,
            expiresAtMillis = dto.expiresAtMillis,
            createdAtMillis = createdMs
        )
    }

    companion object {
        private val ROOM_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        fun generateRoomCode(): String {
            val rng = SecureRandom()
            val part1 = (1..4).map { ROOM_ALPHABET[rng.nextInt(ROOM_ALPHABET.length)] }.joinToString("")
            val part2 = (1..4).map { ROOM_ALPHABET[rng.nextInt(ROOM_ALPHABET.length)] }.joinToString("")
            return "DUET-$part1-$part2"
        }

        fun parseReactions(json: String): Map<String, String> {
            if (json.isBlank()) return emptyMap()
            return try {
                val obj = JSONObject(json)
                buildMap {
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        put(k, obj.optString(k))
                    }
                }
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }
}
