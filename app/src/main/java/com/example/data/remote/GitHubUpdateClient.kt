package com.example.data.remote

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AppReleaseInfo(
    val tagName: String,
    val normalizedVersion: String,
    val title: String,
    val releaseNotes: String,
    val apkDownloadUrl: String?,
    val apkFileName: String?,
    val apkSizeBytes: Long,
    val releasePageUrl: String,
    val publishedAt: String,
    val isNewerThanCurrent: Boolean
)

class GitHubUpdateClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetchLatestRelease(rawRepo: String): Result<AppReleaseInfo> = withContext(Dispatchers.IO) {
        val repo = normalizeRepoSlug(rawRepo)
            ?: return@withContext Result.failure(
                IllegalArgumentException("Enter your GitHub repository as owner/repo (e.g. username/duet).")
            )

        val apiUrl = "https://api.github.com/repos/$repo/releases/latest"
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Duet-Android-Updater/${BuildConfig.VERSION_NAME}")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (response.code == 404) {
                    return@withContext Result.failure(
                        IllegalStateException("No published GitHub Release found for $repo yet. Push a tag (e.g. v1.0.1) or run the Release workflow on GitHub.")
                    )
                }
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("GitHub API returned HTTP ${response.code}")
                    )
                }

                val json = JSONObject(bodyString)
                val tagName = json.optString("tag_name").ifBlank { "v${BuildConfig.VERSION_NAME}" }
                val normalizedRemote = normalizeVersion(tagName)
                val title = json.optString("name").ifBlank { "Duet $tagName" }
                val notes = json.optString("body").trim().ifBlank { "Bug fixes and security improvements." }
                val htmlUrl = json.optString("html_url").ifBlank { "https://github.com/$repo/releases" }
                val publishedAt = json.optString("published_at")

                var apkUrl: String? = null
                var apkName: String? = null
                var apkSize = 0L

                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkName = name
                            apkUrl = asset.optString("browser_download_url").ifBlank { null }
                            apkSize = asset.optLong("size", 0L)
                            break
                        }
                    }
                }

                val isNewer = isVersionNewer(normalizedRemote, BuildConfig.VERSION_NAME)
                Result.success(
                    AppReleaseInfo(
                        tagName = tagName,
                        normalizedVersion = normalizedRemote,
                        title = title,
                        releaseNotes = notes,
                        apkDownloadUrl = apkUrl,
                        apkFileName = apkName,
                        apkSizeBytes = apkSize,
                        releasePageUrl = htmlUrl,
                        publishedAt = publishedAt,
                        isNewerThanCurrent = isNewer
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        private val REPO_SLUG_REGEX = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

        fun defaultConfiguredRepo(): String {
            val fromBuild = BuildConfig.GITHUB_REPO.trim()
            return if (fromBuild.isBlank() || fromBuild == "your-username/duet-messenger") "" else fromBuild
        }

        fun normalizeRepoSlug(rawInput: String): String? {
            var cleaned = rawInput.trim()
                .removeSuffix("/")
                .removeSuffix(".git")
            if (cleaned.startsWith("https://github.com/", ignoreCase = true)) {
                cleaned = cleaned.substringAfter("https://github.com/", "")
            } else if (cleaned.startsWith("http://github.com/", ignoreCase = true)) {
                cleaned = cleaned.substringAfter("http://github.com/", "")
            } else if (cleaned.startsWith("github.com/", ignoreCase = true)) {
                cleaned = cleaned.substringAfter("github.com/", "")
            }
            val segments = cleaned.split("/").filter { it.isNotBlank() }
            if (segments.size < 2) return null
            val slug = "${segments[0]}/${segments[1]}"
            return slug.takeIf { REPO_SLUG_REGEX.matches(it) }
        }

        fun normalizeVersion(version: String): String {
            return version.trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore("-")
                .substringBefore("+")
        }

        fun isVersionNewer(remoteVersion: String, currentVersion: String): Boolean {
            val remoteParts = normalizeVersion(remoteVersion).split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = normalizeVersion(currentVersion).split(".").map { it.toIntOrNull() ?: 0 }
            val maxLen = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        }

        fun openUrlInBrowser(context: Context, url: String): Boolean {
            return try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
        }
    }
}
