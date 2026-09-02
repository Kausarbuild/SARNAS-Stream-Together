package com.example.player

import android.net.Uri

data class ResolvedVideo(
    val originalUrl: String,
    val directPlayableUrl: String,
    val title: String,
    val isGoogleDrive: Boolean = false,
    val requiresPublicPermission: Boolean = false,
    val note: String? = null
)

object VideoUrlResolver {

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
        )
    )

    fun resolve(rawUrl: String): Result<ResolvedVideo> {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("Video URL cannot be empty"))
        }

        // Match Google Drive links
        val googleDriveId = extractGoogleDriveFileId(trimmed)
        if (googleDriveId != null) {
            val directStream = "https://drive.google.com/uc?export=download&id=$googleDriveId"
            return Result.success(
                ResolvedVideo(
                    originalUrl = trimmed,
                    directPlayableUrl = directStream,
                    title = "Google Drive Video ($googleDriveId)",
                    isGoogleDrive = true,
                    requiresPublicPermission = true,
                    note = "Google Drive file. Ensure file sharing is set to 'Anyone with link can view'."
                )
            )
        }

        // General URLs
        val uri = try {
            Uri.parse(trimmed)
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("Invalid URL format"))
        }

        if (uri.scheme == null || (!uri.scheme.equals("http", ignoreCase = true) && !uri.scheme.equals("https", ignoreCase = true))) {
            return Result.failure(IllegalArgumentException("Please provide a valid http:// or https:// video link"))
        }

        val path = uri.path ?: ""
        val fileName = path.substringAfterLast("/", "Video Stream")
        val cleanTitle = if (fileName.contains(".")) fileName.substringBeforeLast(".") else fileName

        return Result.success(
            ResolvedVideo(
                originalUrl = trimmed,
                directPlayableUrl = trimmed,
                title = if (cleanTitle.isNotBlank()) cleanTitle.replace("_", " ").replace("-", " ") else "Direct Stream",
                isGoogleDrive = false,
                requiresPublicPermission = false,
                note = null
            )
        )
    }

    private fun extractGoogleDriveFileId(url: String): String? {
        // Pattern 1: https://drive.google.com/file/d/FILE_ID/view...
        val fileDRegex = Regex("drive\\.google\\.com/file/d/([a-zA-Z0-9_-]+)")
        fileDRegex.find(url)?.groupValues?.getOrNull(1)?.let { return it }

        // Pattern 2: https://drive.google.com/open?id=FILE_ID
        val openIdRegex = Regex("drive\\.google\\.com/open\\?id=([a-zA-Z0-9_-]+)")
        openIdRegex.find(url)?.groupValues?.getOrNull(1)?.let { return it }

        // Pattern 3: https://drive.google.com/uc?id=FILE_ID or export=download&id=FILE_ID
        val ucIdRegex = Regex("[?&]id=([a-zA-Z0-9_-]+)")
        if (url.contains("drive.google.com") || url.contains("docs.google.com")) {
            ucIdRegex.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        }

        return null
    }
}
