package com.example.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.example.data.PlaybackAction
import com.example.data.PlaybackState
import com.example.sync.RoomSyncManager
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentGold
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class SubtitleTrackInfo(
    val groupIndex: Int,
    val trackIndex: Int,
    val language: String?,
    val label: String?,
    val isSelected: Boolean
)

@OptIn(UnstableApi::class)
@Composable
fun SarnasVideoPlayer(
    playbackState: PlaybackState,
    syncManager: RoomSyncManager,
    currentUserName: String,
    modifier: Modifier = Modifier,
    onFullscreenToggle: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Check if current video is a YouTube link
    val youtubeVideoId = remember(playbackState.videoUrl) {
        VideoUrlResolver.extractYouTubeVideoId(playbackState.videoUrl)
    }
    val isYouTube = youtubeVideoId != null || VideoUrlResolver.isYouTubeUrl(playbackState.videoUrl)

    var activePlayUrl by remember { mutableStateOf(playbackState.videoUrl) }
    var triedCandidates by remember { mutableStateOf<List<String>>(emptyList()) }

    var playerError by remember { mutableStateOf<String?>(null) }
    var isBuffering by remember { mutableStateOf(false) }
    var isControlsVisible by remember { mutableStateOf(true) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isMuted by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var isDraggingSlider by remember { mutableStateOf(false) }
    var sliderProgress by remember { mutableFloatStateOf(0f) }
    var showDriveHelp by remember { mutableStateOf(false) }
    var showSubtitleDialog by remember { mutableStateOf(false) }

    // Subtitle tracks detected in current ExoPlayer video
    var availableSubtitleTracks by remember { mutableStateOf<List<SubtitleTrackInfo>>(emptyList()) }
    var customSubtitleUrl by remember { mutableStateOf("") }

    // Auto-hide controls timer
    LaunchedEffect(isControlsVisible, playbackState.isPlaying) {
        if (isControlsVisible && playbackState.isPlaying && !isDraggingSlider) {
            delay(4500)
            isControlsVisible = false
        }
    }

    // Build configured ExoPlayer with HTTP Data Source, cross-protocol redirects & browser User-Agent
    val exoPlayer = remember {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20000)
            .setReadTimeoutMs(30000)
            .setDefaultRequestProperties(
                mapOf(
                    "Accept" to "*/*",
                    "Range" to "bytes=0-"
                )
            )

        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setMatroskaExtractorFlags(MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA)
            .setMp4ExtractorFlags(Mp4Extractor.FLAG_EMIT_RAW_SUBTITLE_DATA)

        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = false
            }
    }

    fun loadMediaUrl(url: String, seekPosition: Long = 0L, playWhenReady: Boolean = false, externalSubUrl: String? = null) {
        if (url.isBlank() || isYouTube) return
        try {
            playerError = null
            isBuffering = true

            val uri = Uri.parse(url)
            val builder = MediaItem.Builder().setUri(uri)

            // Explicit MIME types for streaming playlists
            when {
                url.contains(".m3u8", ignoreCase = true) -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
                url.contains(".mpd", ignoreCase = true) -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
                url.contains(".mp4", ignoreCase = true) -> builder.setMimeType(MimeTypes.VIDEO_MP4)
                url.contains(".mkv", ignoreCase = true) -> builder.setMimeType(MimeTypes.VIDEO_MATROSKA)
                url.contains(".webm", ignoreCase = true) -> builder.setMimeType(MimeTypes.VIDEO_WEBM)
            }

            // External Subtitles (.srt, .vtt)
            val subUri = if (!externalSubUrl.isNullOrBlank()) Uri.parse(externalSubUrl) else null
            if (subUri != null) {
                val subMime = if (externalSubUrl?.contains(".vtt", ignoreCase = true) == true) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
                val subConfig = MediaItem.SubtitleConfiguration.Builder(subUri)
                    .setMimeType(subMime)
                    .setLanguage("en")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
                builder.setSubtitleConfigurations(listOf(subConfig))
            }

            exoPlayer.setMediaItem(builder.build())
            exoPlayer.prepare()
            if (seekPosition > 0L) {
                exoPlayer.seekTo(seekPosition)
            }
            exoPlayer.playWhenReady = playWhenReady
        } catch (e: Exception) {
            playerError = "Failed to load stream: ${e.localizedMessage}"
            isBuffering = false
        }
    }

    // Try alternate candidate stream (e.g. for Google Drive or CDNs with multiple endpoints)
    fun tryNextAlternativeSource() {
        val candidates = VideoUrlResolver.getAlternativeCandidateUrls(playbackState.videoUrl)
        val nextCandidate = candidates.firstOrNull { !triedCandidates.contains(it) }
        if (nextCandidate != null) {
            triedCandidates = triedCandidates + nextCandidate
            activePlayUrl = nextCandidate
            loadMediaUrl(nextCandidate, exoPlayer.currentPosition, true, customSubtitleUrl)
        } else {
            loadMediaUrl(playbackState.videoUrl, 0L, true, customSubtitleUrl)
        }
    }

    // Attach Player Listener
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> isBuffering = true
                    Player.STATE_READY -> {
                        isBuffering = false
                        playerError = null
                        val dur = exoPlayer.duration
                        if (dur > 0) {
                            durationMs = dur
                            syncManager.updateDuration(dur)
                        }
                    }
                    Player.STATE_ENDED -> {
                        isBuffering = false
                    }
                    Player.STATE_IDLE -> {
                        isBuffering = false
                    }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                val list = mutableListOf<SubtitleTrackInfo>()
                for (groupIndex in 0 until tracks.groups.size) {
                    val group = tracks.groups[groupIndex]
                    if (group.type == C.TRACK_TYPE_TEXT) {
                        for (trackIndex in 0 until group.length) {
                            val format = group.getTrackFormat(trackIndex)
                            val isSelected = group.isTrackSelected(trackIndex)
                            list.add(
                                SubtitleTrackInfo(
                                    groupIndex = groupIndex,
                                    trackIndex = trackIndex,
                                    language = format.language ?: "und",
                                    label = format.label ?: format.language ?: "Subtitle ${list.size + 1}",
                                    isSelected = isSelected
                                )
                            )
                        }
                    }
                }
                availableSubtitleTracks = list
            }

            override fun onPlayerError(error: PlaybackException) {
                isBuffering = false
                val isDrive = playbackState.videoUrl.contains("drive.google.com") ||
                        playbackState.videoUrl.contains("googleusercontent.com")

                val alternatives = VideoUrlResolver.getAlternativeCandidateUrls(playbackState.videoUrl)
                val untried = alternatives.filter { !triedCandidates.contains(it) }

                if (isDrive && untried.isNotEmpty()) {
                    val next = untried.first()
                    triedCandidates = triedCandidates + next
                    activePlayUrl = next
                    loadMediaUrl(next, exoPlayer.currentPosition, playbackState.isPlaying, customSubtitleUrl)
                    return
                }

                playerError = when {
                    isDrive ->
                        "Google Drive video cannot be accessed directly.\nMake sure file sharing is set to 'Anyone with the link' (Viewer)."
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                        "Network connection interrupted. Please check your internet connection."
                    error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                        "Server returned an access error (HTTP ${error.message ?: "403/404"}). Check link permissions."
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                        "Video format could not be decoded. Ensure the link points directly to a supported video (.mp4, .m3u8, .mkv, .webm)."
                    else -> "Unable to play video: ${error.localizedMessage ?: "Invalid or restricted video stream"}"
                }
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // When videoUrl changes from sync or user action
    LaunchedEffect(playbackState.videoUrl) {
        if (!isYouTube && playbackState.videoUrl.isNotBlank()) {
            activePlayUrl = playbackState.videoUrl
            triedCandidates = listOf(playbackState.videoUrl)
            loadMediaUrl(playbackState.videoUrl, playbackState.positionMs, playbackState.isPlaying, customSubtitleUrl)
        }
    }

    // Handle incoming synchronization updates (Play/Pause/Seek/Skip)
    LaunchedEffect(playbackState.updatedAt, playbackState.isPlaying, playbackState.lastAction) {
        if (!isYouTube && activePlayUrl.isNotBlank() && exoPlayer.playbackState != Player.STATE_IDLE) {
            if (exoPlayer.playWhenReady != playbackState.isPlaying) {
                exoPlayer.playWhenReady = playbackState.isPlaying
            }

            val elapsed = if (playbackState.isPlaying) System.currentTimeMillis() - playbackState.updatedAt else 0L
            val expectedPos = (playbackState.positionMs + elapsed).coerceAtLeast(0L)

            val drift = kotlin.math.abs(exoPlayer.currentPosition - expectedPos)
            if (drift > 1500L || playbackState.lastAction == PlaybackAction.SEEK ||
                playbackState.lastAction == PlaybackAction.SKIP_FORWARD ||
                playbackState.lastAction == PlaybackAction.SKIP_BACKWARD
            ) {
                exoPlayer.seekTo(expectedPos)
            }
        }
    }

    // Subtitles toggle synchronization
    LaunchedEffect(playbackState.subtitlesEnabled) {
        if (!isYouTube) {
            val parameters = exoPlayer.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !playbackState.subtitlesEnabled)
                .build()
            exoPlayer.trackSelectionParameters = parameters
        }
    }

    // Periodic position updater
    LaunchedEffect(playbackState.isPlaying, isYouTube) {
        while (true) {
            if (!isYouTube && exoPlayer.playbackState == Player.STATE_READY) {
                currentPositionMs = exoPlayer.currentPosition
                val dur = exoPlayer.duration
                if (dur > 0) durationMs = dur
            }
            delay(500)
        }
    }

    // Handle orientation & Fullscreen changes
    fun toggleFullscreen() {
        val newFullscreen = !isFullscreen
        isFullscreen = newFullscreen
        onFullscreenToggle(newFullscreen)

        val activity = context as? Activity
        if (activity != null) {
            if (newFullscreen) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .background(Color.Black)
            .clickable(interactionSource = interactionSource, indication = null) {
                isControlsVisible = !isControlsVisible
            }
            .testTag("sarnas_video_player_box"),
        contentAlignment = Alignment.Center
    ) {
        // Render either YouTube Player or Native ExoPlayer
        if (isYouTube) {
            YouTubeSyncPlayer(
                videoId = youtubeVideoId ?: "dQw4w9WgXcQ",
                playbackState = playbackState,
                syncManager = syncManager,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        // Modern, high contrast subtitle styling
                        subtitleView?.apply {
                            setUserDefaultStyle()
                            setUserDefaultTextSize()
                            setFractionalTextSize(0.053f)
                            setStyle(
                                CaptionStyleCompat(
                                    AndroidColor.WHITE,
                                    AndroidColor.parseColor("#CC111111"),
                                    AndroidColor.TRANSPARENT,
                                    CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                                    AndroidColor.BLACK,
                                    Typeface.SANS_SERIF
                                )
                            )
                        }
                    }
                },
                update = { playerView ->
                    playerView.player = exoPlayer
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Buffering Indicator
        if (isBuffering && !isYouTube) {
            CircularProgressIndicator(
                color = AccentGold,
                strokeWidth = 3.dp,
                modifier = Modifier.size(48.dp)
            )
        }

        // Error Banner
        if (playerError != null && !isYouTube) {
            Surface(
                color = DarkSurface.copy(alpha = 0.95f),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = "Error",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = playerError ?: "Playback Error",
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { tryNextAlternativeSource() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGold),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.AltRoute, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Try Alt Route")
                        }
                        Button(
                            onClick = {
                                loadMediaUrl(playbackState.videoUrl, 0L, true, customSubtitleUrl)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = DarkBackground),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Retry")
                        }
                    }
                }
            }
        }

        // Controls Overlay (Only for non-YouTube or when visible)
        AnimatedVisibility(
            visible = isControlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.8f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.85f)
                            )
                        )
                    )
            ) {
                // Top Action Bar inside Video Player
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = playbackState.videoTitle.ifBlank { "Stream" },
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            ),
                            maxLines = 1
                        )
                        val badge = when {
                            isYouTube -> "YouTube Stream (CC Sync)"
                            playbackState.videoUrl.contains("drive.google.com") || playbackState.videoUrl.contains("googleusercontent.com") -> "Google Drive Video"
                            playbackState.videoUrl.contains(".m3u8") -> "HLS Live Stream"
                            else -> "Direct Video Stream"
                        }
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall.copy(color = AccentGold, fontSize = 11.sp)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Subtitles track dialog button
                        IconButton(
                            onClick = { showSubtitleDialog = true },
                            modifier = Modifier.testTag("player_subtitle_dialog_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Subtitles,
                                contentDescription = "Subtitles",
                                tint = if (playbackState.subtitlesEnabled) AccentGold else TextTertiary
                            )
                        }

                        // CC Quick Toggle
                        IconButton(
                            onClick = {
                                val nextState = !playbackState.subtitlesEnabled
                                syncManager.toggleSubtitles(nextState)
                            },
                            modifier = Modifier.testTag("player_subtitles_toggle")
                        ) {
                            Icon(
                                imageVector = if (playbackState.subtitlesEnabled) Icons.Default.ClosedCaption else Icons.Default.ClosedCaptionDisabled,
                                contentDescription = "Toggle Subtitles",
                                tint = if (playbackState.subtitlesEnabled) AccentGold else TextTertiary
                            )
                        }

                        // Mute button
                        IconButton(
                            onClick = {
                                isMuted = !isMuted
                                exoPlayer.volume = if (isMuted) 0f else 1f
                            },
                            modifier = Modifier.testTag("player_mute_toggle")
                        ) {
                            Icon(
                                imageVector = if (isMuted) Icons.Default.VolumeMute else Icons.Default.VolumeUp,
                                contentDescription = "Mute",
                                tint = TextPrimary
                            )
                        }

                        // Fullscreen Toggle
                        IconButton(
                            onClick = { toggleFullscreen() },
                            modifier = Modifier.testTag("player_fullscreen_toggle")
                        ) {
                            Icon(
                                imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = "Fullscreen",
                                tint = TextPrimary
                            )
                        }
                    }
                }

                // Center Play / Rewind / Skip Controls (For both standard and YouTube playback)
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Rewind 10s
                    IconButton(
                        onClick = {
                            val newPos = (currentPositionMs - 10000L).coerceAtLeast(0L)
                            currentPositionMs = newPos
                            if (!isYouTube) exoPlayer.seekTo(newPos)
                            syncManager.sendSkipBackward(newPos)
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .background(DarkSurfaceVariant.copy(alpha = 0.6f), CircleShape)
                            .testTag("player_rewind_btn")
                    ) {
                        Icon(Icons.Default.Replay10, contentDescription = "Rewind 10s", tint = TextPrimary, modifier = Modifier.size(26.dp))
                    }

                    // Main Play / Pause Button
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(AccentGold)
                            .clickable {
                                val shouldPlay = !playbackState.isPlaying
                                if (!isYouTube) {
                                    exoPlayer.playWhenReady = shouldPlay
                                }
                                if (shouldPlay) {
                                    syncManager.sendPlay(currentPositionMs)
                                } else {
                                    syncManager.sendPause(currentPositionMs)
                                }
                            }
                            .testTag("player_main_play_pause_btn"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                            tint = DarkBackground,
                            modifier = Modifier.size(38.dp)
                        )
                    }

                    // Forward 10s
                    IconButton(
                        onClick = {
                            val newPos = currentPositionMs + 10000L
                            currentPositionMs = newPos
                            if (!isYouTube) exoPlayer.seekTo(newPos)
                            syncManager.sendSkipForward(newPos)
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .background(DarkSurfaceVariant.copy(alpha = 0.6f), CircleShape)
                            .testTag("player_forward_btn")
                    ) {
                        Icon(Icons.Default.Forward10, contentDescription = "Forward 10s", tint = TextPrimary, modifier = Modifier.size(26.dp))
                    }
                }

                // Bottom Timeline Bar (Progress Slider & Timestamps)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    val maxDur = if (durationMs > 0) durationMs else (playbackState.durationMs.takeIf { it > 0 } ?: 1L)
                    val activePos = if (isDraggingSlider) (sliderProgress * maxDur).toLong() else currentPositionMs

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatTime(activePos),
                            style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary, fontWeight = FontWeight.Medium)
                        )
                        Text(
                            text = formatTime(maxDur),
                            style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary, fontWeight = FontWeight.Medium)
                        )
                    }

                    Slider(
                        value = if (maxDur > 0) (activePos.toFloat() / maxDur.toFloat()).coerceIn(0f, 1f) else 0f,
                        onValueChange = { frac ->
                            isDraggingSlider = true
                            sliderProgress = frac
                        },
                        onValueChangeFinished = {
                            isDraggingSlider = false
                            val targetMs = (sliderProgress * maxDur).toLong()
                            currentPositionMs = targetMs
                            if (!isYouTube) exoPlayer.seekTo(targetMs)
                            syncManager.sendSeek(targetMs)
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = AccentGold,
                            activeTrackColor = AccentGold,
                            inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .testTag("player_timeline_slider")
                    )
                }
            }
        }
    }

    // Subtitle & Captions Selection Dialog
    if (showSubtitleDialog) {
        SubtitleSelectionDialog(
            isYouTube = isYouTube,
            subtitlesEnabled = playbackState.subtitlesEnabled,
            availableTracks = availableSubtitleTracks,
            onToggleSubtitles = { enabled ->
                syncManager.toggleSubtitles(enabled)
            },
            onSelectTrack = { track ->
                // Apply specific track to ExoPlayer
                val tracks = exoPlayer.currentTracks
                val group = tracks.groups.getOrNull(track.groupIndex)
                if (group != null) {
                    val override = androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex)
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .setOverrideForType(override)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .build()
                }
                syncManager.toggleSubtitles(true)
                showSubtitleDialog = false
            },
            onApplyExternalSubtitle = { subUrl ->
                customSubtitleUrl = subUrl
                loadMediaUrl(playbackState.videoUrl, currentPositionMs, playbackState.isPlaying, subUrl)
                syncManager.toggleSubtitles(true)
                showSubtitleDialog = false
            },
            onDismiss = { showSubtitleDialog = false }
        )
    }
}

@Composable
fun SubtitleSelectionDialog(
    isYouTube: Boolean,
    subtitlesEnabled: Boolean,
    availableTracks: List<SubtitleTrackInfo>,
    onToggleSubtitles: (Boolean) -> Unit,
    onSelectTrack: (SubtitleTrackInfo) -> Unit,
    onApplyExternalSubtitle: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var externalUrlInput by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = DarkSurface,
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
            modifier = Modifier.fillMaxWidth().testTag("subtitles_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Subtitles & Captions",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Toggle switch
                Surface(
                    color = if (subtitlesEnabled) DarkSurfaceElevated else DarkSurfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (subtitlesEnabled) AccentGold else DarkBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleSubtitles(!subtitlesEnabled) }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (subtitlesEnabled) "Subtitles: Enabled (On)" else "Subtitles: Disabled (Off)",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = if (subtitlesEnabled) AccentGold else TextPrimary
                            )
                        )
                        Icon(
                            imageVector = if (subtitlesEnabled) Icons.Default.ClosedCaption else Icons.Default.ClosedCaptionDisabled,
                            contentDescription = null,
                            tint = if (subtitlesEnabled) AccentGold else TextSecondary
                        )
                    }
                }

                if (!isYouTube) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Available Tracks in Video:",
                        style = MaterialTheme.typography.labelMedium.copy(color = TextSecondary)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (availableTracks.isEmpty()) {
                        Text(
                            text = "No embedded subtitle tracks detected in file.",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextTertiary)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(availableTracks) { track ->
                                Surface(
                                    color = if (track.isSelected) DarkSurfaceElevated else DarkSurfaceVariant,
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, if (track.isSelected) AccentGold else DarkBorder),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectTrack(track) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = track.label ?: "Track ${track.trackIndex + 1} (${track.language})",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = if (track.isSelected) AccentGold else TextPrimary,
                                                fontWeight = FontWeight.Medium
                                            )
                                        )
                                        if (track.isSelected) {
                                            Icon(Icons.Default.Check, contentDescription = "Active", tint = AccentGold, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Or Load Subtitle by URL (.srt / .vtt):",
                        style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = externalUrlInput,
                        onValueChange = { externalUrlInput = it },
                        placeholder = { Text("https://.../subtitles.vtt") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentGold,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedContainerColor = DarkSurfaceVariant,
                            unfocusedContainerColor = DarkSurfaceVariant
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            if (externalUrlInput.isNotBlank()) {
                                onApplyExternalSubtitle(externalUrlInput.trim())
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = DarkBackground),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().height(40.dp)
                    ) {
                        Text("Apply Subtitle URL", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                    }
                } else {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "YouTube automated and creator captions are supported. Toggling CC will synchronize captions across all connected devices.",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextTertiary)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant, contentColor = TextPrimary),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(42.dp)
                ) {
                    Text("Done")
                }
            }
        }
    }
}

private fun formatTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hours = minutes / 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes % 60, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
