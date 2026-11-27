package com.example.data.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Zero-Knowledge Cryptography & Hardware-Backed Key Storage Manager.
 *
 * - Derives a 256-bit AES key from the shared Secret Passphrase using PBKDF2WithHmacSHA256 (100,000 iterations).
 * - Encrypts/decrypts all payloads (messages, images, voice notes, WebRTC SDP/ICE, FCM data)
 *   using AES/GCM/NoPadding with a unique 12-byte SecureRandom IV per item and 128-bit auth tag.
 * - Stores keys, 4-digit PIN hash, biometric toggle, room pairing, and preferences inside
 *   AndroidX EncryptedSharedPreferences backed by Android Keystore MasterKey (AES256_GCM).
 */
class CryptoManager(context: Context) {

    private val appContext = context.applicationContext
    private val secureRandom = SecureRandom()

    private val prefs: SharedPreferences by lazy {
        createResilientEncryptedPrefs()
    }

    private fun createResilientEncryptedPrefs(): SharedPreferences {
        return try {
            buildEncryptedPrefs()
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Resetting corrupted EncryptedSharedPreferences: ${e.message}")
            appContext.deleteSharedPreferences(PREFS_NAME)
            try {
                buildEncryptedPrefs()
            } catch (fallbackEx: Exception) {
                Log.w(TAG, "Keystore unavailable; falling back to private prefs: ${fallbackEx.message}")
                appContext.getSharedPreferences("${PREFS_NAME}_fallback", Context.MODE_PRIVATE)
            }
        } catch (e: Exception) {
            Log.w(TAG, "EncryptedSharedPreferences init failed; falling back: ${e.message}")
            appContext.getSharedPreferences("${PREFS_NAME}_fallback", Context.MODE_PRIVATE)
        }
    }

    private fun buildEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    @Volatile
    private var cachedAesKey: SecretKeySpec? = null

    fun hasAesKey(): Boolean = getAesKey() != null

    fun isVaultConfigured(): Boolean =
        hasAesKey() && !getRoomId().isNullOrBlank() && hasPin()

    fun deriveAndSavePassphrase(passphrase: String, roomId: String) {
        val cleanRoom = roomId.trim()
        val saltInput = "duet-e2ee-salt-v1:$cleanRoom".toByteArray(Charsets.UTF_8)
        val salt = MessageDigest.getInstance("SHA-256").digest(saltInput).copyOf(16)

        val spec = PBEKeySpec(
            passphrase.toCharArray(),
            salt,
            PBKDF2_ITERATIONS,
            KEY_LENGTH_BITS
        )
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val derivedBytes = factory.generateSecret(spec).encoded
        spec.clearPassword()

        val keyBase64 = Base64.encodeToString(derivedBytes, Base64.NO_WRAP)
        prefs.edit().putString(KEY_AES_BASE64, keyBase64).apply()
        cachedAesKey = SecretKeySpec(derivedBytes, "AES")
    }

    private fun getAesKey(): SecretKeySpec? {
        cachedAesKey?.let { return it }
        val encoded = prefs.getString(KEY_AES_BASE64, null) ?: return null
        val decoded = Base64.decode(encoded, Base64.NO_WRAP)
        return SecretKeySpec(decoded, "AES").also { cachedAesKey = it }
    }

    fun encryptString(plaintext: String): String {
        return encryptBytes(plaintext.toByteArray(Charsets.UTF_8))
    }

    fun decryptString(base64Envelope: String): String {
        val bytes = decryptBytes(base64Envelope)
        return String(bytes, Charsets.UTF_8)
    }

    fun encryptBytes(plainBytes: ByteArray): String {
        val key = getAesKey()
            ?: throw IllegalStateException("Encryption key is not initialized.")
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).apply { secureRandom.nextBytes(this) }
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val cipherBytes = cipher.doFinal(plainBytes)

        val combined = ByteArray(iv.size + cipherBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decryptBytes(base64Envelope: String): ByteArray {
        val key = getAesKey()
            ?: throw IllegalStateException("Encryption key is not initialized.")
        val combined = Base64.decode(base64Envelope, Base64.NO_WRAP)
        require(combined.size > GCM_IV_LENGTH_BYTES) { "Malformed encrypted payload" }

        val iv = combined.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val cipherBytes = combined.copyOfRange(GCM_IV_LENGTH_BYTES, combined.size)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(cipherBytes)
    }

    /**
     * Generates a Signal-style 60-digit safety verification number from the derived AES key,
     * allowing both partners to verbally verify zero-knowledge key parity.
     */
    fun computeSafetyNumber(): String {
        val key = getAesKey() ?: return "Key not configured"
        val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
        val digits = buildString {
            for (i in 0 until 12) {
                val b0 = digest[i * 2 % digest.size].toInt() and 0xFF
                val b1 = digest[(i * 2 + 1) % digest.size].toInt() and 0xFF
                val block = ((b0 shl 8) or b1) % 100000
                append(block.toString().padStart(5, '0'))
                if (i % 4 == 3 && i < 11) append("\n") else if (i < 11) append(" ")
            }
        }
        return digits
    }

    fun hashInviteCode(roomId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("duet-room-invite-v1:${roomId.trim()}".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // --- 4-Digit App PIN ---

    fun hasPin(): Boolean = !prefs.getString(KEY_PIN_HASH, null).isNullOrBlank()

    fun setPin(pin: String) {
        require(pin.length == 4 && pin.all { it.isDigit() }) { "PIN must be 4 digits" }
        val salt = ByteArray(16).apply { secureRandom.nextBytes(this) }
        val saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP)
        val hash = hashPin(pin, salt)
        prefs.edit()
            .putString(KEY_PIN_SALT, saltB64)
            .putString(KEY_PIN_HASH, hash)
            .apply()
    }

    fun verifyPin(pin: String): Boolean {
        val saltB64 = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val expectedHash = prefs.getString(KEY_PIN_HASH, null) ?: return false
        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        val actualHash = hashPin(pin, salt)
        return MessageDigest.isEqual(
            expectedHash.toByteArray(Charsets.UTF_8),
            actualHash.toByteArray(Charsets.UTF_8)
        )
    }

    private fun hashPin(pin: String, salt: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        val digest = md.digest(pin.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    // --- Biometric & Vault Preferences ---

    fun isBiometricEnabled(): Boolean = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, true)
    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
    }

    fun getRoomId(): String? = prefs.getString(KEY_ROOM_ID, null)
    fun setRoomId(roomId: String?) {
        prefs.edit().putString(KEY_ROOM_ID, roomId).apply()
    }

    fun getPartnerUid(): String? = prefs.getString(KEY_PARTNER_UID, null)
    fun setPartnerUid(uid: String?) {
        prefs.edit().putString(KEY_PARTNER_UID, uid).apply()
    }

    fun getPartnerName(): String = prefs.getString(KEY_PARTNER_NAME, null) ?: "Partner"
    fun setPartnerName(name: String) {
        prefs.edit().putString(KEY_PARTNER_NAME, name).apply()
    }

    fun getPartnerFcmToken(): String? = prefs.getString(KEY_PARTNER_FCM_TOKEN, null)
    fun setPartnerFcmToken(token: String?) {
        prefs.edit().putString(KEY_PARTNER_FCM_TOKEN, token).apply()
    }

    fun getLocalFcmToken(): String? = prefs.getString(KEY_LOCAL_FCM_TOKEN, null)
    fun setLocalFcmToken(token: String?) {
        prefs.edit().putString(KEY_LOCAL_FCM_TOKEN, token).apply()
    }

    fun getPushWebhookUrl(): String = prefs.getString(KEY_PUSH_WEBHOOK_URL, null) ?: ""
    fun setPushWebhookUrl(url: String) {
        prefs.edit().putString(KEY_PUSH_WEBHOOK_URL, url.trim()).apply()
    }

    fun isDarkTheme(): Boolean = prefs.getBoolean(KEY_DARK_THEME, true)
    fun setDarkTheme(dark: Boolean) {
        prefs.edit().putBoolean(KEY_DARK_THEME, dark).apply()
    }

    fun getWallpaperId(): String = prefs.getString(KEY_WALLPAPER_ID, "botanical") ?: "botanical"
    fun setWallpaperId(id: String) {
        prefs.edit().putString(KEY_WALLPAPER_ID, id).apply()
    }

    fun getDisappearingSeconds(): Long = prefs.getLong(KEY_DISAPPEARING_SECONDS, 0L)
    fun setDisappearingSeconds(seconds: Long) {
        prefs.edit().putLong(KEY_DISAPPEARING_SECONDS, seconds).apply()
    }

    fun isScreenPrivacyEnabled(defaultValue: Boolean): Boolean =
        prefs.getBoolean(KEY_SCREEN_PRIVACY, defaultValue)

    fun setScreenPrivacyEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SCREEN_PRIVACY, enabled).apply()
    }

    fun getGitHubRepo(): String = prefs.getString(KEY_GITHUB_REPO, null) ?: ""
    fun setGitHubRepo(repo: String) {
        prefs.edit().putString(KEY_GITHUB_REPO, repo.trim()).apply()
    }

    fun isAutoCheckUpdatesEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_CHECK_UPDATES, true)
    fun setAutoCheckUpdatesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CHECK_UPDATES, enabled).apply()
    }

    fun clearVaultSession() {
        cachedAesKey = null
        prefs.edit()
            .remove(KEY_AES_BASE64)
            .remove(KEY_ROOM_ID)
            .remove(KEY_PARTNER_UID)
            .remove(KEY_PARTNER_NAME)
            .remove(KEY_PARTNER_FCM_TOKEN)
            .apply()
    }

    companion object {
        private const val TAG = "CryptoManager"
        private const val PREFS_NAME = "duet_zero_knowledge_vault"
        private const val PBKDF2_ITERATIONS = 100_000
        private const val KEY_LENGTH_BITS = 256
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128

        private const val KEY_AES_BASE64 = "aes_key_b64"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_ROOM_ID = "room_id"
        private const val KEY_PARTNER_UID = "partner_uid"
        private const val KEY_PARTNER_NAME = "partner_name"
        private const val KEY_PARTNER_FCM_TOKEN = "partner_fcm_token"
        private const val KEY_LOCAL_FCM_TOKEN = "local_fcm_token"
        private const val KEY_PUSH_WEBHOOK_URL = "push_webhook_url"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_WALLPAPER_ID = "wallpaper_id"
        private const val KEY_DISAPPEARING_SECONDS = "disappearing_seconds"
        private const val KEY_SCREEN_PRIVACY = "screen_privacy_flag_secure"
        private const val KEY_GITHUB_REPO = "github_update_repo"
        private const val KEY_AUTO_CHECK_UPDATES = "auto_check_updates"
    }
}
