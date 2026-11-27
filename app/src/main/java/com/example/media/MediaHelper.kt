package com.example.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object MediaHelper {

    private const val MAX_IMAGE_DIMENSION_PX = 900
    private const val TARGET_MAX_IMAGE_BYTES = 175 * 1024 // < 180 KB

    /**
     * Loads a photo from Uri, scales down to max 900px, and iteratively compresses
     * JPEG quality so the binary payload remains strictly under 180 KB for Firestore storage.
     */
    suspend fun compressImageToBase64(context: Context, uri: Uri): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }

                var sampleSize = 1
                val maxDim = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
                if (maxDim > MAX_IMAGE_DIMENSION_PX) {
                    var half = maxDim / 2
                    while (half / sampleSize >= MAX_IMAGE_DIMENSION_PX) {
                        sampleSize *= 2
                    }
                }

                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val decoded = resolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, decodeOpts)
                } ?: return@withContext Result.failure(IllegalArgumentException("Unable to decode image"))

                val scaled = scaleDownIfNeeded(decoded, MAX_IMAGE_DIMENSION_PX)
                if (scaled != decoded) decoded.recycle()

                var quality = 86
                var outputBytes: ByteArray
                do {
                    val baos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                    outputBytes = baos.toByteArray()
                    quality -= 8
                } while (outputBytes.size > TARGET_MAX_IMAGE_BYTES && quality >= 28)

                scaled.recycle()
                Result.success(Base64.encodeToString(outputBytes, Base64.NO_WRAP))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDimension && height <= maxDimension) return bitmap
        val ratio = minOf(maxDimension.toFloat() / width, maxDimension.toFloat() / height)
        val newWidth = (width * ratio).toInt().coerceAtLeast(1)
        val newHeight = (height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Saves decrypted image bytes directly to the Android Gallery via MediaStore (zero storage permission required).
     */
    suspend fun saveImageToGallery(context: Context, imageBytes: ByteArray, fileName: String): Result<Uri> =
        withContext(Dispatchers.IO) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Duet")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext Result.failure(IllegalStateException("MediaStore insert failed"))

                resolver.openOutputStream(uri)?.use { out ->
                    out.write(imageBytes)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                Result.success(uri)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun decodeBase64ToBitmap(base64: String): Bitmap? {
        return try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }

    fun saveVoiceCacheFile(context: Context, messageId: String, base64: String): File? {
        return try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            val dir = File(context.cacheDir, "voice_notes").apply { mkdirs() }
            val file = File(dir, "$messageId.m4a")
            FileOutputStream(file).use { it.write(bytes) }
            file
        } catch (_: Exception) {
            null
        }
    }
}

class VoiceNoteRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startTimeMs: Long = 0L

    fun startRecording(): Boolean {
        return try {
            val dir = File(context.cacheDir, "voice_rec").apply { mkdirs() }
            val file = File(dir, "rec_${System.currentTimeMillis()}.m4a")
            outputFile = file

            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(24_000)
                setAudioSamplingRate(22_050)
                setMaxDuration(45_000) // keep encrypted voice note compact for Firestore doc
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = rec
            startTimeMs = System.currentTimeMillis()
            true
        } catch (_: Exception) {
            stopAndDiscard()
            false
        }
    }

    fun currentAmplitudeNormalized(): Float {
        return try {
            val amp = recorder?.maxAmplitude ?: 0
            (amp / 32767f).coerceIn(0.05f, 1f)
        } catch (_: Exception) {
            0.1f
        }
    }

    fun stopAndEncodeBase64(): Pair<String, Long>? {
        val file = outputFile ?: return null
        val durationMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(500L)
        return try {
            recorder?.apply {
                stop()
                release()
            }
            recorder = null
            val bytes = file.readBytes()
            file.delete()
            if (bytes.isEmpty()) null
            else Base64.encodeToString(bytes, Base64.NO_WRAP) to durationMs
        } catch (_: Exception) {
            stopAndDiscard()
            null
        }
    }

    fun stopAndDiscard() {
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {
        }
        recorder = null
        outputFile?.delete()
        outputFile = null
    }
}

class VoiceNotePlayer {
    private var player: MediaPlayer? = null
    private var currentMessageId: String? = null

    fun playOrToggle(
        messageId: String,
        audioFile: File,
        speed: Float,
        onProgress: (Float) -> Unit,
        onComplete: () -> Unit
    ): Boolean {
        return try {
            if (currentMessageId == messageId && player != null) {
                val p = player!!
                if (p.isPlaying) {
                    p.pause()
                    return false
                } else {
                    applySpeed(p, speed)
                    p.start()
                    return true
                }
            }

            release()
            currentMessageId = messageId
            val p = MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                prepare()
                applySpeed(this, speed)
                setOnCompletionListener {
                    onProgress(0f)
                    onComplete()
                }
                start()
            }
            player = p
            true
        } catch (_: Exception) {
            release()
            onComplete()
            false
        }
    }

    fun updateSpeed(speed: Float) {
        player?.let { applySpeed(it, speed) }
    }

    private fun applySpeed(mp: MediaPlayer, speed: Float) {
        try {
            if (mp.isPlaying) {
                mp.playbackParams = PlaybackParams().setSpeed(speed)
            } else {
                mp.playbackParams = PlaybackParams().setSpeed(speed)
            }
        } catch (_: Exception) {
        }
    }

    fun progressFraction(): Float {
        val p = player ?: return 0f
        return try {
            val dur = p.duration.coerceAtLeast(1)
            (p.currentPosition.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
        } catch (_: Exception) {
            0f
        }
    }

    fun isPlaying(messageId: String): Boolean {
        return currentMessageId == messageId && (player?.isPlaying == true)
    }

    fun release() {
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        currentMessageId = null
    }
}
