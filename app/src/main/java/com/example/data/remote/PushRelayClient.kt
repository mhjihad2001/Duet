package com.example.data.remote

import android.util.Log
import com.example.BuildConfig
import com.example.data.crypto.CryptoManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Spark-Plan Zero-Billing Push Trigger Hook.
 *
 * Fires an asynchronous OkHttp POST request to a configurable Cloudflare Worker or
 * Google Apps Script webhook URL (BuildConfig.PUSH_WEBHOOK_URL or Settings override)
 * with { targetToken, encryptedPayload, type } to trigger FCM HTTP v1 API data-only pushes.
 */
class PushRelayClient(private val cryptoManager: CryptoManager) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun sendPushAsync(
        targetToken: String?,
        type: String, // "MESSAGE" or "CALL"
        payloadJson: JSONObject
    ) {
        val token = targetToken?.trim().orEmpty()
        if (token.isEmpty()) return

        val webhookUrl = cryptoManager.getPushWebhookUrl()
            .ifBlank { BuildConfig.PUSH_WEBHOOK_URL }
            .trim()
        if (webhookUrl.isBlank() || !webhookUrl.startsWith("http") || webhookUrl.contains("example.invalid")) return

        scope.launch {
            try {
                val encryptedPayload = cryptoManager.encryptString(payloadJson.toString())
                val bodyJson = JSONObject().apply {
                    put("targetToken", token)
                    put("encryptedPayload", encryptedPayload)
                    put("type", type)
                }

                val request = Request.Builder()
                    .url(webhookUrl)
                    .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Push webhook returned HTTP ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Push webhook dispatch skipped/failed: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "PushRelayClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
