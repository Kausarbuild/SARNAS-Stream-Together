package com.example.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
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
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.example.data.PlaybackAction
import com.example.data.PlaybackState
import com.example.sync.RoomSyncManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    var activePlayUrl by remember { mutableStateOf(playbackState.videoUrl) }
    var triedCandidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var candidateIndex by remember { mutableIntStateOf(0) }

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

        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = false
            }
    }

    fun loadMediaUrl(url: String, seekPosition: Long = 0L, playWhenReady: Boolean = false) {
        if (url.isBlank()) return
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
            loadMediaUrl(nextCandidate, exoPlayer.currentPosition, true)
        } else {
            // Re-try original with clean state
            loadMediaUrl(playbackState.videoUrl, 0L, true)
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

            override fun onPlayerError(error: PlaybackException) {
                isBuffering = false
                val isDrive = playbackState.videoUrl.contains("drive.google.com") ||
                        playbackState.videoUrl.contains("googleusercontent.com")

                val alternatives = VideoUrlResolver.getAlternativeCandidateUrls(playbackState.videoUrl)
                val untried = alternatives.filter { !triedCandidates.contains(it) }

                if (isDrive && untried.isNotEmpty()) {
                    // Automatically attempt fallback endpoint before giving up
                    val next = untried.first()
                    triedCandidates = triedCandidates + next
                    activePlayUrl = next
                    loadMediaUrl(next, exoPlayer.currentPosition, playbackState.isPlaying)
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
        if (playbackState.videoUrl.isNotBlank()) {
            activePlayUrl = playbackState.videoUrl
            triedCandidates = listOf(playbackState.videoUrl)
            loadMediaUrl(playbackState.videoUrl, playbackState.positionMs, playbackState.isPlaying)
        }
    }

    // Handle incoming synchronization updates (Play/Pause/Seek/Skip)
    LaunchedEffect(playbackState.updatedAt, playbackState.isPlaying, playbackState.lastAction) {
        if (activePlayUrl.isNotBlank() && exoPlayer.playbackState != Player.STATE_IDLE) {
            // Apply play / pause state
            if (exoPlayer.playWhenReady != playbackState.isPlaying) {
                exoPlayer.playWhenReady = playbackState.isPlaying
            }

            // Calculate expected position taking elapsed time into account
            val elapsed = if (playbackState.isPlaying) System.currentTimeMillis() - playbackState.updatedAt else 0L
            val expectedPos = (playbackState.positionMs + elapsed).coerceAtLeast(0L)

            // Drift detection: if drift > 1500ms or explicit SEEK/SKIP, align player
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
        val parameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !playbackState.subtitlesEnabled)
            .build()
        exoPlayer.trackSelectionParameters = parameters
    }

    // Periodic position updater
    LaunchedEffect(playbackState.isPlaying) {
        while (true) {
            if (exoPlayer.playbackState == Player.STATE_READY) {
                currentPositionMs = exoPlayer.currentPosition
                val dur = exoPlayer.duration
                if (dur > 0) durationMs = dur
                if (!isDraggingSlider && durationMs > 0) {
                    sliderProgress = (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                }
            }
            delay(400)
        }
    }

    // Handle orientation / fullscreen
    fun toggleFullscreen() {
        val newFs = !isFullscreen
        isFullscreen = newFs
        val activity = context as? Activity
        activity?.let {
            it.requestedOrientation = if (newFs) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        onFullscreenToggle(newFs)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                isControlsVisible = !isControlsVisible
            }
            .testTag("video_player_container"),
        contentAlignment = Alignment.Center
    ) {
        // ExoPlayer Surface
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Buffering Indicator
        if (isBuffering && playerError == null) {
            CircularProgressIndicator(
                color = Color(0xFFE5A93C),
                strokeWidth = 3.dp,
                modifier = Modifier.size(48.dp)
            )
        }

        // Error message overlay with actionable troubleshooting
        if (playerError != null) {
            Surface(
                color = Color(0xF014161F),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE5A93C).copy(alpha = 0.4f)),
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth(0.92f)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = "Error",
                        tint = Color(0xFFE5A93C),
                        modifier = Modifier.size(38.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Video Stream Issue",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF0F2F8)
                        )
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = playerError ?: "Error",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Color(0xFFB0B5C6),
                            lineHeight = 18.sp
                        ),
                        modifier = Modifier.padding(horizontal = 6.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Google Drive specific help box
                    if (playbackState.videoUrl.contains("drive.google.com") || playbackState.videoUrl.contains("googleusercontent.com")) {
                        Surface(
                            color = Color(0xFF1E2130),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showDriveHelp = !showDriveHelp }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.HelpOutline, contentDescription = null, tint = Color(0xFF4E95FF), modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("How to make Google Drive links public", style = MaterialTheme.typography.labelMedium.copy(color = Color(0xFF4E95FF), fontWeight = FontWeight.SemiBold))
                                    }
                                    Text(if (showDriveHelp) "Hide" else "Show", style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFFB0B5C6)))
                                }

                                if (showDriveHelp) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("1. Open Google Drive and find the video.\n2. Tap 'Share' (or 3 dots > Share).\n3. Under 'General access', change from 'Restricted' to 'Anyone with the link' (Viewer).\n4. Tap 'Copy link' and paste into SARNAS.",
                                        style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFF0F2F8), fontSize = 11.sp, lineHeight = 16.sp))
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    // Action Buttons: Try Alternative Route | Retry
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                tryNextAlternativeSource()
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).height(40.dp)
                        ) {
                            Icon(Icons.Default.AltRoute, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFF4E95FF))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Alternate CDN", style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF4E95FF)))
                        }

                        Button(
                            onClick = {
                                playerError = null
                                loadMediaUrl(activePlayUrl, exoPlayer.currentPosition, true)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFE5A93C),
                                contentColor = Color(0xFF0B0C10)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).height(40.dp).testTag("retry_video_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Retry", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }
        }

        // Video Controls Overlay (Play, Pause, +/- 15s, Seek Bar, Mute, CC, Fullscreen)
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
                                Color.Black.copy(alpha = 0.7f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.85f)
                            )
                        )
                    )
            ) {
                // Top overlay: Video Title & Live Sync Badge
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (playbackState.videoTitle.isNotBlank()) playbackState.videoTitle else "No Video Selected",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                fontSize = 15.sp
                            ),
                            maxLines = 1
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(Color(0xFF4CAF50), CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Synced with room",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = Color(0xFFB0B5C6),
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }
                }

                // Center Controls: Rewind 15s | Play/Pause | Forward 15s
                Row(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.75f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 15s Skip Backward
                    IconButton(
                        onClick = {
                            val cur = exoPlayer.currentPosition
                            syncManager.sendSkipBackward(cur, currentUserName)
                        },
                        modifier = Modifier
                            .size(52.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                            .testTag("skip_backward_15_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Replay10,
                            contentDescription = "Rewind 15 seconds",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    // Main Play / Pause Button
                    IconButton(
                        onClick = {
                            val cur = exoPlayer.currentPosition
                            if (playbackState.isPlaying) {
                                syncManager.sendPause(cur, currentUserName)
                            } else {
                                syncManager.sendPlay(cur, currentUserName)
                            }
                        },
                        modifier = Modifier
                            .size(72.dp)
                            .background(Color(0xFFE5A93C), CircleShape)
                            .testTag("play_pause_button")
                    ) {
                        Icon(
                            imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                            tint = Color(0xFF0B0C10),
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    // 15s Skip Forward
                    IconButton(
                        onClick = {
                            val cur = exoPlayer.currentPosition
                            syncManager.sendSkipForward(cur, durationMs, currentUserName)
                        },
                        modifier = Modifier
                            .size(52.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                            .testTag("skip_forward_15_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Forward10,
                            contentDescription = "Forward 15 seconds",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                // Bottom Control Bar: Time, Seekbar, CC, Mute, Fullscreen
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // Scrubbable Slider
                    Slider(
                        value = if (isDraggingSlider) sliderProgress else (if (durationMs > 0) (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f),
                        onValueChange = { newValue ->
                            isDraggingSlider = true
                            sliderProgress = newValue
                        },
                        onValueChangeFinished = {
                            val targetMs = (sliderProgress * durationMs).toLong()
                            syncManager.sendSeek(targetMs, currentUserName)
                            isDraggingSlider = false
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFE5A93C),
                            activeTrackColor = Color(0xFFE5A93C),
                            inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .testTag("video_seek_bar")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Timecode
                        val formattedCurrent = formatDuration(if (isDraggingSlider) (sliderProgress * durationMs).toLong() else currentPositionMs)
                        val formattedTotal = formatDuration(durationMs)
                        Text(
                            text = "$formattedCurrent / $formattedTotal",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = Color.White.copy(alpha = 0.85f),
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp
                            )
                        )

                        // Action icons: CC, Mute, Fullscreen
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // CC / Subtitles Toggle
                            IconButton(
                                onClick = {
                                    syncManager.setSubtitlesEnabled(!playbackState.subtitlesEnabled)
                                },
                                modifier = Modifier.size(38.dp).testTag("subtitles_toggle_button")
                            ) {
                                Icon(
                                    imageVector = if (playbackState.subtitlesEnabled) Icons.Default.ClosedCaption else Icons.Default.ClosedCaptionDisabled,
                                    contentDescription = "Subtitles",
                                    tint = if (playbackState.subtitlesEnabled) Color(0xFFE5A93C) else Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            // Mute / Unmute
                            IconButton(
                                onClick = {
                                    isMuted = !isMuted
                                    exoPlayer.volume = if (isMuted) 0f else 1f
                                },
                                modifier = Modifier.size(38.dp).testTag("mute_toggle_button")
                            ) {
                                Icon(
                                    imageVector = if (isMuted) Icons.Default.VolumeMute else Icons.Default.VolumeUp,
                                    contentDescription = if (isMuted) "Unmute" else "Mute",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            // Fullscreen Toggle
                            IconButton(
                                onClick = { toggleFullscreen() },
                                modifier = Modifier.size(38.dp).testTag("fullscreen_toggle_button")
                            ) {
                                Icon(
                                    imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                    contentDescription = "Toggle Fullscreen",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
