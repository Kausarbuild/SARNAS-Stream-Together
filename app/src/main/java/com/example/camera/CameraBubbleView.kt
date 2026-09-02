package com.example.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.example.data.RoomParticipant
import java.io.ByteArrayOutputStream

/**
 * Real-time floating camera bubble:
 * - When self & camera on: renders live front camera preview and captures frames to broadcast.
 * - When peer & camera on: renders incoming live video feed from peer.
 * - When camera off: renders avatar with camera-off indicator.
 * - Displays live mic status and participant name tag.
 */
@Composable
fun CameraBubble(
    participant: RoomParticipant,
    isSelf: Boolean,
    isCameraEnabled: Boolean,
    isMicrophoneEnabled: Boolean,
    peerBitmap: Bitmap? = null,
    onFrameCaptured: ((ByteArray) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val isSpeaking = if (isSelf) isMicrophoneEnabled else !participant.isMuted
    val showCamera = if (isSelf) isCameraEnabled else participant.isCameraOn

    Box(
        modifier = modifier
            .size(76.dp)
            .shadow(12.dp, CircleShape)
            .border(
                width = if (isSpeaking) 2.5.dp else 1.5.dp,
                color = if (isSpeaking) Color(0xFFE5A93C) else Color(0xFF2A2F42),
                shape = CircleShape
            )
            .clip(CircleShape)
            .background(Color(0xFF14161F)),
        contentAlignment = Alignment.Center
    ) {
        if (showCamera && isSelf) {
            // Live front camera preview for local user with frame capture
            CameraPreview(
                context = context,
                onFrameCaptured = onFrameCaptured,
                modifier = Modifier.fillMaxSize()
            )
        } else if (showCamera && !isSelf && peerBitmap != null) {
            // Live video stream from remote peer
            Image(
                bitmap = peerBitmap.asImageBitmap(),
                contentDescription = participant.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else if (!participant.avatarUri.isNullOrBlank()) {
            AsyncImage(
                model = participant.avatarUri,
                contentDescription = participant.name,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Initial placeholder
            val initial = participant.name.take(1).uppercase()
            val parsedColor = try {
                Color(android.graphics.Color.parseColor(participant.avatarColorHex))
            } catch (e: Exception) {
                Color(0xFFE5A93C)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(parsedColor.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initial,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 24.sp
                    )
                )
            }
        }

        // Overlay status indicators
        if (!showCamera) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.40f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.VideocamOff,
                    contentDescription = "Camera Off",
                    tint = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(22.dp)
                )
            }
        } else if (!isSelf && peerBitmap == null) {
            // Camera is on but first frame is buffering
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "LIVE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE5A93C)
                    )
                )
            }
        }

        // Mic badge at bottom-right of bubble
        val isMuted = if (isSelf) !isMicrophoneEnabled else participant.isMuted
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(2.dp)
                .size(20.dp)
                .background(
                    color = if (isMuted) Color(0xFFE53935) else Color(0xFF1E2230),
                    shape = CircleShape
                )
                .border(1.dp, Color(0xFF0B0C10), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = if (isMuted) "Muted" else "Speaking",
                tint = Color.White,
                modifier = Modifier.size(11.dp)
            )
        }

        // Name tag at top
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 3.dp)
                .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                .padding(horizontal = 6.dp, vertical = 1.dp)
        ) {
            Text(
                text = if (isSelf) "You" else participant.name.take(6),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                ),
                maxLines = 1
            )
        }
    }
}

@Composable
fun CameraPreview(
    context: Context,
    onFrameCaptured: ((ByteArray) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    previewView?.let { pView ->
                        it.setSurfaceProvider(pView.surfaceProvider)
                    }
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                var lastCapture = 0L
                imageAnalysis.setAnalyzer(executor) { imageProxy ->
                    val now = System.currentTimeMillis()
                    if (onFrameCaptured != null && (now - lastCapture > 1500L)) {
                        lastCapture = now
                        try {
                            val plane = imageProxy.planes[0]
                            val buffer = plane.buffer
                            val width = imageProxy.width
                            val height = imageProxy.height

                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bitmap.copyPixelsFromBuffer(buffer)

                            val scaled = Bitmap.createScaledBitmap(bitmap, 120, 120, true)
                            val stream = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, 35, stream)
                            val bytes = stream.toByteArray()
                            onFrameCaptured(bytes)

                            if (scaled != bitmap) scaled.recycle()
                            bitmap.recycle()
                        } catch (e: Exception) {
                            // Ignored
                        }
                    }
                    imageProxy.close()
                }

                val cameraSelector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (exc: Exception) {
                Log.e("CameraBubble", "Use case binding failed", exc)
            }
        }, executor)

        onDispose {
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()
            } catch (e: Exception) {
                Log.e("CameraBubble", "Unbind failed", e)
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                previewView = this
            }
        },
        modifier = modifier
    )
}

@Composable
fun ParticipantBubblesStack(
    participants: List<RoomParticipant>,
    currentUserId: String,
    isCameraEnabled: Boolean,
    isMicrophoneEnabled: Boolean,
    peerVideoFrames: Map<String, Bitmap> = emptyMap(),
    onFrameCaptured: ((ByteArray) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Render local user first
        val self = participants.firstOrNull { it.id == currentUserId }
        if (self != null) {
            CameraBubble(
                participant = self,
                isSelf = true,
                isCameraEnabled = isCameraEnabled,
                isMicrophoneEnabled = isMicrophoneEnabled,
                onFrameCaptured = onFrameCaptured
            )
        }

        // Render other participants (up to 3 others cleanly)
        participants.filter { it.id != currentUserId }.take(3).forEach { peer ->
            CameraBubble(
                participant = peer,
                isSelf = false,
                isCameraEnabled = peer.isCameraOn,
                isMicrophoneEnabled = !peer.isMuted,
                peerBitmap = peerVideoFrames[peer.id]
            )
        }
    }
}
