package com.example.data.model

/**
 * Strict 2-User Whitelist Gate.
 *
 * Populate USER_1_UID and USER_2_UID with your two Firebase Auth UIDs
 * (shown in Settings -> Security Info after signing in with Google)
 * to permanently restrict app login to exclusively those two accounts.
 * Leave empty during initial pairing to allow 2-seat Couple Room pairing
 * (enforced at max 2 members in firestore.rules).
 */
object SecurityWhitelist {
    const val USER_1_UID: String = ""
    const val USER_2_UID: String = ""

    val ALLOWED_UIDS: Set<String> = listOf(USER_1_UID, USER_2_UID)
        .filter { it.isNotBlank() }
        .toSet()

    fun isAllowed(uid: String): Boolean {
        if (uid.isBlank()) return false
        return ALLOWED_UIDS.isEmpty() || uid in ALLOWED_UIDS
    }
}
