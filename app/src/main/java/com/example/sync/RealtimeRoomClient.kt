package com.example.sync

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RealtimeRoomClient(
    private val onMessageReceived: (RealtimeMessage) -> Unit
) {
    private val tag = "RealtimeRoomClient"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep WebSocket open indefinitely
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var activeWebSocket: WebSocket? = null
    private var currentTopic: String? = null
    private var isIntentionalClose = false

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    fun connect(roomId: String) {
        disconnect()
        isIntentionalClose = false

        val sanitizedTopic = formatTopic(roomId)
        currentTopic = sanitizedTopic
        _connectionState.value = ConnectionState.CONNECTING

        val wsUrl = "wss://ntfy.sh/$sanitizedTopic/ws"
        val request = Request.Builder()
            .url(wsUrl)
            .header("User-Agent", "SARNAS-Android-WatchParty/1.0")
            .build()

        activeWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(tag, "WebSocket opened to topic: $sanitizedTopic")
                _connectionState.value = ConnectionState.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingRaw(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(tag, "WebSocket closing: $code / $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(tag, "WebSocket closed: $code / $reason")
                if (!isIntentionalClose) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "WebSocket failure: ${t.message}", t)
                if (!isIntentionalClose) {
                    _connectionState.value = ConnectionState.ERROR
                    scheduleReconnect()
                }
            }
        })
    }

    fun broadcast(message: RealtimeMessage) {
        val topic = currentTopic ?: formatTopic(message.roomId)
        val jsonString = serializeMessage(message)

        scope.launch {
            try {
                // 1. Send via active WebSocket if open
                activeWebSocket?.send(jsonString)

                // 2. Dual Publish via HTTP POST to guaranteed ntfy.sh broadcast bus
                val postUrl = "https://ntfy.sh/$topic"
                val mediaType = "text/plain; charset=utf-8".toMediaType()
                val body = jsonString.toRequestBody(mediaType)
                val request = Request.Builder()
                    .url(postUrl)
                    .header("Title", "sarnas-sync")
                    .header("Priority", "urgent")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(tag, "HTTP broadcast response not successful: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to broadcast message: ${e.message}", e)
            }
        }
    }

    private fun handleIncomingRaw(raw: String) {
        try {
            val root = JSONObject(raw)
            val event = root.optString("event", "")

            // ntfy.sh sends "message" events with actual payload in "message" string field
            val payloadStr = if (event == "message" && root.has("message")) {
                root.getString("message")
            } else {
                raw
            }

            val msgJson = if (payloadStr.startsWith("{")) {
                JSONObject(payloadStr)
            } else {
                root
            }

            val message = deserializeMessage(msgJson)
            if (message != null) {
                onMessageReceived(message)
            }
        } catch (e: Exception) {
            Log.w(tag, "Error parsing incoming packet: ${e.message}")
        }
    }

    private fun serializeMessage(msg: RealtimeMessage): String {
        val obj = JSONObject()
        obj.put("type", msg.type)
        obj.put("roomId", msg.roomId)
        obj.put("senderId", msg.senderId)
        obj.put("senderName", msg.senderName)
        msg.avatarUri?.let { obj.put("avatarUri", it) }
        msg.avatarColorHex?.let { obj.put("avatarColorHex", it) }
        obj.put("isHost", msg.isHost)
        obj.put("isCameraOn", msg.isCameraOn)
        obj.put("isMuted", msg.isMuted)
        msg.videoUrl?.let { obj.put("videoUrl", it) }
        msg.videoTitle?.let { obj.put("videoTitle", it) }
        msg.isPlaying?.let { obj.put("isPlaying", it) }
        msg.positionMs?.let { obj.put("positionMs", it) }
        msg.durationMs?.let { obj.put("durationMs", it) }
        msg.subtitlesEnabled?.let { obj.put("subtitlesEnabled", it) }
        msg.chatText?.let { obj.put("chatText", it) }
        msg.emoji?.let { obj.put("emoji", it) }
        obj.put("timestamp", msg.timestamp)
        return obj.toString()
    }

    private fun deserializeMessage(json: JSONObject): RealtimeMessage? {
        val type = json.optString("type", "")
        if (type.isBlank()) return null

        return RealtimeMessage(
            type = type,
            roomId = json.optString("roomId", ""),
            senderId = json.optString("senderId", ""),
            senderName = json.optString("senderName", "Friend"),
            avatarUri = if (json.has("avatarUri")) json.optString("avatarUri", null) else null,
            avatarColorHex = json.optString("avatarColorHex", "#E5A93C"),
            isHost = json.optBoolean("isHost", false),
            isCameraOn = json.optBoolean("isCameraOn", false),
            isMuted = json.optBoolean("isMuted", false),
            videoUrl = if (json.has("videoUrl")) json.optString("videoUrl", null) else null,
            videoTitle = if (json.has("videoTitle")) json.optString("videoTitle", null) else null,
            isPlaying = if (json.has("isPlaying")) json.optBoolean("isPlaying", false) else null,
            positionMs = if (json.has("positionMs")) json.optLong("positionMs", 0L) else null,
            durationMs = if (json.has("durationMs")) json.optLong("durationMs", 0L) else null,
            subtitlesEnabled = if (json.has("subtitlesEnabled")) json.optBoolean("subtitlesEnabled", false) else null,
            chatText = if (json.has("chatText")) json.optString("chatText", null) else null,
            emoji = if (json.has("emoji")) json.optString("emoji", null) else null,
            timestamp = json.optLong("timestamp", System.currentTimeMillis())
        )
    }

    private fun scheduleReconnect() {
        if (isIntentionalClose) return
        scope.launch {
            delay(2000)
            val topic = currentTopic
            if (topic != null && !isIntentionalClose) {
                Log.d(tag, "Attempting reconnect to $topic...")
                connect(topic)
            }
        }
    }

    fun disconnect() {
        isIntentionalClose = true
        activeWebSocket?.close(1000, "User left room")
        activeWebSocket = null
        currentTopic = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    companion object {
        fun formatTopic(roomId: String): String {
            val clean = roomId.trim().lowercase().replace(Regex("[^a-z0-9]"), "_")
            return "sarnas_v1_room_$clean"
        }
    }
}
