package com.example.player

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class ResolvedVideo(
    val originalUrl: String,
    val directPlayableUrl: String,
    val title: String,
    val isGoogleDrive: Boolean = false,
    val isDropbox: Boolean = false,
    val isHlsStream: Boolean = false,
    val isDashStream: Boolean = false,
    val fallbackUrls: List<String> = emptyList(),
    val requiresPublicPermission: Boolean = false,
    val note: String? = null
)

object VideoUrlResolver {

    private val okHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // Curated high quality legal open-source streaming presets for instant watch party testing
    val CURATED_STREAMS = listOf(
        ResolvedVideo(
            originalUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            directPlayableUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            title = "Big Buck Bunny (Animation)",
            note = "High Quality 1080p Open Stream"
        ),
        ResolvedVideo(
            originalUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4",
            directPlayableUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4",
            title = "Tears of Steel (Sci-Fi 4K)",
            note = "High Quality 1080p Open Stream"
        ),
        ResolvedVideo(
            originalUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
            directPlayableUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
            title = "Elephants Dream (CGI Short)",
            note = "High Quality Open Stream"
        ),
        ResolvedVideo(
            originalUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4",
            directPlayableUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4",
            title = "Sintel (Fantasy Epic)",
            note = "High Quality Open Stream"
        ),
        ResolvedVideo(
            originalUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
            directPlayableUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
            title = "Big Buck Bunny (HLS Live Stream)",
            isHlsStream = true,
            note = "Adaptive HLS Multi-bitrate Stream"
        )
    )

    fun resolve(rawUrl: String): Result<ResolvedVideo> {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("Video URL cannot be empty"))
        }

        // Check for YouTube URLs and provide clear guidance
        if (trimmed.contains("youtube.com/watch") || trimmed.contains("youtu.be/") || trimmed.contains("youtube.com/shorts")) {
            return Result.failure(
                IllegalArgumentException(
                    "Direct YouTube web URLs are protected by DRM and cannot be played directly via stream URL. " +
                    "Please use a direct video link (.mp4, .m3u8, .mkv), Google Drive public link, or Dropbox link!"
                )
            )
        }

        // 1. Match Google Drive links
        val googleDriveId = extractGoogleDriveFileId(trimmed)
        if (googleDriveId != null) {
            // Google Drive provides several streaming/download endpoints.
            // 1. Direct Google User Content CDN (bypasses virus check interstitial for media playback)
            val primaryDriveUrl = "https://drive.usercontent.google.com/download?id=$googleDriveId&export=download&authuser=0&confirm=t"
            val secondaryDriveUrl = "https://lh3.googleusercontent.com/d/$googleDriveId"
            val tertiaryDriveUrl = "https://drive.google.com/uc?export=download&confirm=t&id=$googleDriveId"

            return Result.success(
                ResolvedVideo(
                    originalUrl = trimmed,
                    directPlayableUrl = primaryDriveUrl,
                    title = "Google Drive Video",
                    isGoogleDrive = true,
                    fallbackUrls = listOf(secondaryDriveUrl, tertiaryDriveUrl),
                    requiresPublicPermission = true,
                    note = "Make sure file sharing in Google Drive is set to 'Anyone with the link can view'."
                )
            )
        }

        // 2. Match Dropbox links
        if (trimmed.contains("dropbox.com")) {
            val directDropboxUrl = convertDropboxUrl(trimmed)
            return Result.success(
                ResolvedVideo(
                    originalUrl = trimmed,
                    directPlayableUrl = directDropboxUrl,
                    title = extractTitleFromUrl(trimmed, "Dropbox Video"),
                    isDropbox = true,
                    fallbackUrls = listOf(
                        trimmed.replace("www.dropbox.com", "dl.dropboxusercontent.com").replace("?dl=0", "").replace("&dl=0", "")
                    ),
                    requiresPublicPermission = false,
                    note = "Dropbox direct stream resolved."
                )
            )
        }

        // 3. Match OneDrive links
        if (trimmed.contains("1drv.ms") || trimmed.contains("onedrive.live.com")) {
            val directOneDriveUrl = if (trimmed.contains("?")) "$trimmed&download=1" else "$trimmed?download=1"
            return Result.success(
                ResolvedVideo(
                    originalUrl = trimmed,
                    directPlayableUrl = directOneDriveUrl,
                    title = "OneDrive Video",
                    fallbackUrls = emptyList(),
                    requiresPublicPermission = false,
                    note = null
                )
            )
        }

        // 4. General / Direct URLs (MP4, MKV, WebM, HLS m3u8, DASH mpd)
        val uri = try {
            Uri.parse(trimmed)
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("Invalid URL format"))
        }

        if (uri.scheme == null || (!uri.scheme.equals("http", ignoreCase = true) && !uri.scheme.equals("https", ignoreCase = true))) {
            return Result.failure(IllegalArgumentException("Please provide a valid http:// or https:// video link"))
        }

        val isHls = trimmed.contains(".m3u8", ignoreCase = true)
        val isDash = trimmed.contains(".mpd", ignoreCase = true)
        val cleanTitle = extractTitleFromUrl(trimmed, if (isHls) "HLS Live Stream" else "Direct Video Stream")

        return Result.success(
            ResolvedVideo(
                originalUrl = trimmed,
                directPlayableUrl = trimmed,
                title = cleanTitle,
                isGoogleDrive = false,
                isHlsStream = isHls,
                isDashStream = isDash,
                fallbackUrls = emptyList(),
                requiresPublicPermission = false,
                note = null
            )
        )
    }

    suspend fun resolveAsync(rawUrl: String): ResolvedVideo = withContext(Dispatchers.IO) {
        val syncResult = resolve(rawUrl)
        if (syncResult.isFailure) {
            throw syncResult.exceptionOrNull() ?: IllegalArgumentException("Invalid URL")
        }

        val initial = syncResult.getOrThrow()
        if (!initial.isGoogleDrive) {
            return@withContext initial
        }

        // For Google Drive, perform a fast pre-flight check to see which endpoint responds with video/streaming headers
        val endpointsToTry = listOf(initial.directPlayableUrl) + initial.fallbackUrls
        for (candidate in endpointsToTry) {
            try {
                val request = Request.Builder()
                    .url(candidate)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
                    .head() // Try HEAD first
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val contentType = response.header("Content-Type") ?: ""
                val statusCode = response.code

                if (response.isSuccessful && !contentType.contains("text/html", ignoreCase = true)) {
                    response.close()
                    return@withContext initial.copy(
                        directPlayableUrl = candidate,
                        fallbackUrls = endpointsToTry.filter { it != candidate }
                    )
                }
                response.close()
            } catch (e: Exception) {
                // Continue to next candidate
            }
        }

        return@withContext initial
    }

    fun getAlternativeCandidateUrls(url: String): List<String> {
        val googleDriveId = extractGoogleDriveFileId(url)
        if (googleDriveId != null) {
            return listOf(
                "https://drive.usercontent.google.com/download?id=$googleDriveId&export=download&authuser=0&confirm=t",
                "https://lh3.googleusercontent.com/d/$googleDriveId",
                "https://drive.google.com/uc?export=download&confirm=t&id=$googleDriveId"
            ).filter { it != url }
        }
        return emptyList()
    }

    private fun extractGoogleDriveFileId(url: String): String? {
        // Pattern 1: https://drive.google.com/file/d/FILE_ID/view...
        val fileDRegex = Regex("drive\\.google\\.com/file/d/([a-zA-Z0-9_-]+)")
        fileDRegex.find(url)?.groupValues?.getOrNull(1)?.let { return it }

        // Pattern 2: https://drive.google.com/open?id=FILE_ID
        val openIdRegex = Regex("drive\\.google\\.com/open\\?id=([a-zA-Z0-9_-]+)")
        openIdRegex.find(url)?.groupValues?.getOrNull(1)?.let { return it }

        // Pattern 3: https://drive.usercontent.google.com/download?id=FILE_ID
        val userContentRegex = Regex("drive\\.usercontent\\.google\\.com/download\\?id=([a-zA-Z0-9_-]+)")
        userContentRegex.find(url)?.groupValues?.getOrNull(1)?.let { return it }

        // Pattern 4: https://lh3.googleusercontent.com/d/FILE_ID
        val lh3Regex = Regex("lh3\\.googleusercontent\\.com/d/([a-zA-Z0-9_-]+)")
        lh3Regex.find(url)?.groupValues?.getOrNull(1)?.let { return it }

        // Pattern 5: https://drive.google.com/uc?id=FILE_ID or export=download&id=FILE_ID
        val ucIdRegex = Regex("[?&]id=([a-zA-Z0-9_-]+)")
        if (url.contains("drive.google.com") || url.contains("docs.google.com")) {
            ucIdRegex.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        }

        return null
    }

    private fun convertDropboxUrl(url: String): String {
        var clean = url.trim()
        if (clean.contains("?dl=0")) {
            clean = clean.replace("?dl=0", "?raw=1")
        } else if (clean.contains("&dl=0")) {
            clean = clean.replace("&dl=0", "&raw=1")
        } else if (!clean.contains("raw=1") && !clean.contains("dl=1")) {
            clean = if (clean.contains("?")) "$clean&raw=1" else "$clean?raw=1"
        }
        return clean.replace("www.dropbox.com", "dl.dropboxusercontent.com")
    }

    private fun extractTitleFromUrl(url: String, defaultTitle: String): String {
        return try {
            val uri = Uri.parse(url)
            val path = uri.path ?: ""
            val fileName = path.substringAfterLast("/", "")
            if (fileName.isNotBlank() && fileName.contains(".")) {
                fileName.substringBeforeLast(".")
                    .replace("_", " ")
                    .replace("-", " ")
                    .replace("%20", " ")
            } else {
                defaultTitle
            }
        } catch (e: Exception) {
            defaultTitle
        }
    }
}
