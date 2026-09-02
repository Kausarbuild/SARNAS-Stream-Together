package com.example.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.PlaybackAction
import com.example.data.PlaybackState
import com.example.sync.RoomSyncManager

/**
 * High-performance, synchronized YouTube Player utilizing YouTube IFrame Player API.
 * Supports synchronized play, pause, seek, duration updates, and captions across devices.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeSyncPlayer(
    videoId: String,
    playbackState: PlaybackState,
    syncManager: RoomSyncManager,
    modifier: Modifier = Modifier,
    onPlayerReady: () -> Unit = {}
) {
    val context = LocalContext.current
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isApiReady by remember { mutableStateOf(false) }
    var lastSyncedActionTime by remember { mutableStateOf(0L) }

    class WebAppInterface {
        @JavascriptInterface
        fun onPlayerReady(durationSeconds: Float) {
            isApiReady = true
            val durMs = (durationSeconds * 1000).toLong()
            if (durMs > 0) {
                syncManager.updateDuration(durMs)
            }
            onPlayerReady()
        }

        @JavascriptInterface
        fun onStateChange(state: Int, currentSec: Float) {
            val posMs = (currentSec * 1000).toLong()
            when (state) {
                1 -> { // PLAYING
                    if (!playbackState.isPlaying) {
                        syncManager.sendPlay(posMs)
                    }
                }
                2 -> { // PAUSED
                    if (playbackState.isPlaying) {
                        syncManager.sendPause(posMs)
                    }
                }
            }
        }

        @JavascriptInterface
        fun onTimeUpdate(currentSec: Float, durationSec: Float) {
            val durMs = (durationSec * 1000).toLong()
            if (durMs > 0 && playbackState.durationMs <= 0) {
                syncManager.updateDuration(durMs)
            }
        }
    }

    val jsInterface = remember { WebAppInterface() }

    val htmlData = remember(videoId) {
        """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <style>
                * { margin: 0; padding: 0; box-sizing: border-box; }
                html, body { width: 100%; height: 100%; background-color: #000; overflow: hidden; }
                #player { width: 100%; height: 100%; pointer-events: auto; }
            </style>
        </head>
        <body>
            <div id="player"></div>
            <script>
                var tag = document.createElement('script');
                tag.src = "https://www.youtube.com/iframe_api";
                var firstScriptTag = document.getElementsByTagName('script')[0];
                firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);

                var player;
                function onYouTubeIframeAPIReady() {
                    player = new YT.Player('player', {
                        height: '100%',
                        width: '100%',
                        videoId: '$videoId',
                        playerVars: {
                            'autoplay': 0,
                            'playsinline': 1,
                            'controls': 1,
                            'rel': 0,
                            'modestbranding': 1,
                            'fs': 0,
                            'enablejsapi': 1,
                            'cc_load_policy': 1,
                            'origin': 'https://sarnas.stream'
                        },
                        events: {
                            'onReady': onPlayerReady,
                            'onStateChange': onPlayerStateChange
                        }
                    });
                }

                function onPlayerReady(event) {
                    var duration = player.getDuration();
                    if (window.AndroidBridge) {
                        window.AndroidBridge.onPlayerReady(duration);
                    }
                    setInterval(function() {
                        if (player && player.getCurrentTime) {
                            var cur = player.getCurrentTime();
                            var dur = player.getDuration();
                            if (window.AndroidBridge) {
                                window.AndroidBridge.onTimeUpdate(cur, dur);
                            }
                        }
                    }, 1000);
                }

                function onPlayerStateChange(event) {
                    if (window.AndroidBridge && player && player.getCurrentTime) {
                        window.AndroidBridge.onStateChange(event.data, player.getCurrentTime());
                    }
                }

                function play() {
                    if (player && player.playVideo) { player.playVideo(); }
                }

                function pause() {
                    if (player && player.pauseVideo) { player.pauseVideo(); }
                }

                function seek(seconds) {
                    if (player && player.seekTo) { player.seekTo(seconds, true); }
                }

                function toggleSubtitles(enabled) {
                    if (player && player.loadModule) {
                        if (enabled) {
                            player.loadModule("captions");
                            player.setOption("captions", "track", {"languageCode": "en"});
                        } else {
                            player.unloadModule("captions");
                        }
                    }
                }
            </script>
        </body>
        </html>
        """.trimIndent()
    }

    // Sync external play / pause / seek events to YouTube player
    LaunchedEffect(playbackState.updatedAt, playbackState.isPlaying, isApiReady) {
        val webView = webViewInstance ?: return@LaunchedEffect
        if (!isApiReady) return@LaunchedEffect

        val targetSeconds = (playbackState.positionMs / 1000.0)
        val isExplicitAction = playbackState.updatedAt != lastSyncedActionTime
        lastSyncedActionTime = playbackState.updatedAt

        if (playbackState.isPlaying) {
            if (isExplicitAction) {
                val elapsed = (System.currentTimeMillis() - playbackState.updatedAt) / 1000.0
                val adjustedSeconds = targetSeconds + elapsed.coerceAtLeast(0.0)
                webView.evaluateJavascript("seek($adjustedSeconds); play();", null)
            } else {
                webView.evaluateJavascript("play();", null)
            }
        } else {
            if (isExplicitAction) {
                webView.evaluateJavascript("seek($targetSeconds); pause();", null)
            } else {
                webView.evaluateJavascript("pause();", null)
            }
        }
    }

    // Closed Captions / Subtitles toggle
    LaunchedEffect(playbackState.subtitlesEnabled, isApiReady) {
        val webView = webViewInstance ?: return@LaunchedEffect
        if (!isApiReady) return@LaunchedEffect
        webView.evaluateJavascript("toggleSubtitles(${playbackState.subtitlesEnabled});", null)
    }

    Box(
        modifier = modifier.background(androidx.compose.ui.graphics.Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(Color.BLACK)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        cacheMode = WebSettings.LOAD_DEFAULT
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                    }
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {}
                    addJavascriptInterface(jsInterface, "AndroidBridge")
                    loadDataWithBaseURL("https://sarnas.stream", htmlData, "text/html", "UTF-8", null)
                    webViewInstance = this
                }
            },
            update = { view ->
                // Handled in LaunchedEffects
            },
            modifier = Modifier.fillMaxSize()
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewInstance?.destroy()
            webViewInstance = null
        }
    }
}
