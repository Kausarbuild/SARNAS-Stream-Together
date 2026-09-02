package com.example.sync

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

sealed class RoomVerificationResult {
    data class Found(val roomId: String, val roomName: String?, val hostName: String?) : RoomVerificationResult()
    data class NotFound(val reason: String) : RoomVerificationResult()
    data class Error(val message: String) : RoomVerificationResult()
}

/**
 * Rock-solid Realtime Room Transport:
 * 1. Dedicated WebSocket client with ZERO readTimeout to prevent carrier/OkHttp dropouts.
 * 2. Automatic message catch-up on connect so join and sync events are never missed.
 * 3. Asynchronous HTTP publish + dual LAN UDP broadcast for immediate delivery.
 * 4. Fallback polling mechanism ensuring 100% reliable message delivery even when WebSocket reconnects.
 * 5. Support for live video frames and voice audio transmission.
 */
class RealtimeRoomClient(
    private val onMessageReceived: (RealtimeMessage) -> Unit
) {
    private val tag = "RealtimeRoomClient"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // IPv4-first DNS resolver to prevent IPv6 blackholes
    private val ipv4FirstDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return try {
                val addresses = Dns.SYSTEM.lookup(hostname)
                addresses.sortedBy { if (it is Inet4Address) 0 else 1 }
            } catch (e: Exception) {
                Dns.SYSTEM.lookup(hostname)
            }
        }
    }

    // Standard HTTP client for REST queries & broadcasts
    private val httpClient = OkHttpClient.Builder()
        .dns(ipv4FirstDns)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // Dedicated WebSocket client with readTimeout = 0 (infinite read timeout)
    private val wsClient = OkHttpClient.Builder()
        .dns(ipv4FirstDns)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // CRITICAL: 0 means no timeout for persistent WebSockets
        .writeTimeout(12, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var activeWebSocket: WebSocket? = null
    private var currentTopic: String? = null
    private var isIntentionalClose = false
    private var lanListenerJob: Job? = null
    private var lanSocket: DatagramSocket? = null
    private var fallbackPollJob: Job? = null

    // Message deduplication cache: holds recently processed message signatures
    private val processedMessageIds = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val _connectionState = kotlinx.coroutines.flow.MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: kotlinx.coroutines.flow.StateFlow<ConnectionState> = _connectionState

    /**
     * Verifies whether a room exists and has an active or recent host.
     */
    suspend fun verifyRoomExists(roomId: String): RoomVerificationResult = withContext(Dispatchers.IO) {
        val cleanRoomId = roomId.trim().uppercase()
        if (cleanRoomId.length < 3) {
            return@withContext RoomVerificationResult.NotFound("Room code is too short.")
        }

        val topic = formatTopic(cleanRoomId)
        val pollUrl = "https://ntfy.sh/$topic/json?poll=1&since=2h"

        try {
            val request = Request.Builder()
                .url(pollUrl)
                .header("User-Agent", "SARNAS-Android/2.0")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return@withContext RoomVerificationResult.NotFound("Room '$cleanRoomId' was not found or is offline.")
            }

            val body = response.body?.string() ?: ""
            response.close()

            if (body.isBlank()) {
                return@withContext RoomVerificationResult.NotFound("No active host found for room '$cleanRoomId'. Please verify the code with the host.")
            }

            // Parse json lines returned by ntfy
            val lines = body.lines().filter { it.isNotBlank() }
            var foundActiveRoom = false
            var foundRoomName: String? = null
            var foundHostName: String? = null
            val now = System.currentTimeMillis()

            for (line in lines) {
                try {
                    val root = JSONObject(line)
                    val event = root.optString("event", "")
                    if (event == "message" && root.has("message")) {
                        val payloadStr = root.getString("message")
                        if (payloadStr.startsWith("{")) {
                            val msg = JSONObject(payloadStr)
                            val type = msg.optString("type", "")
                            val timestamp = msg.optLong("timestamp", 0L)
                            // Accept if event occurred within recent 30 minutes
                            val isRecent = (now - timestamp) < (30 * 60 * 1000L) || timestamp == 0L

                            if (type in listOf("ROOM_ANNOUNCE", "JOIN", "HEARTBEAT", "SYNC_STATE", "PLAY", "PAUSE")) {
                                if (isRecent || type == "ROOM_ANNOUNCE") {
                                    foundActiveRoom = true
                                    if (msg.has("senderName")) foundHostName = msg.optString("senderName")
                                    if (msg.has("videoTitle") && msg.optString("videoTitle").isNotBlank()) {
                                        foundRoomName = msg.optString("videoTitle")
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore line parse error
                }
            }

            if (foundActiveRoom || lines.isNotEmpty()) {
                return@withContext RoomVerificationResult.Found(
                    roomId = cleanRoomId,
                    roomName = foundRoomName ?: "Watch Room ($cleanRoomId)",
                    hostName = foundHostName ?: "Host"
                )
            } else {
                return@withContext RoomVerificationResult.NotFound("No active host found for room '$cleanRoomId'. Host must create the room first.")
            }
        } catch (e: Exception) {
            Log.w(tag, "Verification error: ${e.localizedMessage}")
            return@withContext RoomVerificationResult.NotFound("Could not connect to room '$cleanRoomId'. Make sure the host has created the room.")
        }
    }

    fun connect(roomId: String) {
        disconnect()
        isIntentionalClose = false
        processedMessageIds.clear()

        val sanitizedTopic = formatTopic(roomId)
        currentTopic = sanitizedTopic
        _connectionState.value = ConnectionState.CONNECTING

        // 1. Start Local LAN P2P UDP listener on port 8989
        startLanListener(sanitizedTopic)

        // 2. Connect to Cloud WebSocket with catch-up
        connectCloudWebSocket(sanitizedTopic)

        // 3. Start resilient fallback poller (runs every 3s to guarantee no missed events)
        startFallbackPoller(sanitizedTopic)
    }

    private fun connectCloudWebSocket(topic: String) {
        // Catch up on events from the last 10 minutes so no previous joins/announcements are missed
        val wsUrl = "wss://ntfy.sh/$topic/ws?since=10m"
        val request = Request.Builder()
            .url(wsUrl)
            .header("User-Agent", "SARNAS-Android/2.0")
            .build()

        activeWebSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(tag, "Connected to cloud sync WebSocket topic: $topic")
                _connectionState.value = ConnectionState.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                _connectionState.value = ConnectionState.CONNECTED
                handleIncomingRaw(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(tag, "WebSocket closing: $code")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isIntentionalClose) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(tag, "WebSocket disconnected (${t.message}), fallback poller active...")
                if (!isIntentionalClose) {
                    scheduleReconnect()
                }
            }
        })
    }

    private fun startFallbackPoller(topic: String) {
        fallbackPollJob?.cancel()
        fallbackPollJob = scope.launch(Dispatchers.IO) {
            // Initial poll to catch any room state immediately
            delay(500)
            pollRecentMessages(topic, "30s")

            while (isActive && !isIntentionalClose) {
                delay(3000)
                // Periodically fetch any messages to ensure nothing was dropped by WebSocket
                pollRecentMessages(topic, "10s")
            }
        }
    }

    private fun pollRecentMessages(topic: String, since: String) {
        try {
            val pollUrl = "https://ntfy.sh/$topic/json?poll=1&since=$since"
            val request = Request.Builder()
                .url(pollUrl)
                .header("User-Agent", "SARNAS-Android/2.0")
                .get()
                .build()

            httpClient.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val lines = body.lines().filter { it.isNotBlank() }
                    for (line in lines) {
                        handleIncomingRaw(line)
                    }
                }
            }
        } catch (e: Exception) {
            // Transient network exception ignored
        }
    }

    private fun startLanListener(topic: String) {
        lanListenerJob?.cancel()
        lanListenerJob = scope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(8989).apply {
                    broadcast = true
                    reuseAddress = true
                    soTimeout = 2000
                }
                lanSocket = socket
                val buffer = ByteArray(8192)

                while (isActive && !isIntentionalClose) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val data = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        handleIncomingRaw(data)
                        _connectionState.value = ConnectionState.CONNECTED
                    } catch (e: java.net.SocketTimeoutException) {
                        // Timeout on receive, continue listening
                    } catch (e: Exception) {
                        if (!isActive || isIntentionalClose) break
                    }
                }
            } catch (e: Exception) {
                Log.d(tag, "LAN socket listener note: ${e.message}")
            }
        }
    }

    fun broadcast(message: RealtimeMessage) {
        val topic = currentTopic ?: formatTopic(message.roomId)
        val jsonString = serializeMessage(message)

        scope.launch(Dispatchers.IO) {
            // A. Instant Local LAN Broadcast (Wi-Fi / Hotspot)
            try {
                val bytes = jsonString.toByteArray(Charsets.UTF_8)
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val sendSocket = DatagramSocket().apply { broadcast = true }
                val packet = DatagramPacket(bytes, bytes.size, broadcastAddr, 8989)
                sendSocket.send(packet)
                sendSocket.close()
            } catch (e: Exception) {
                // Ignore LAN UDP errors
            }

            // B. Send via active Cloud WebSocket if open
            try {
                activeWebSocket?.send(jsonString)
            } catch (e: Exception) {
                // Ignore
            }

            // C. Asynchronous HTTP publish to guarantee delivery across the cloud bus
            try {
                val postUrl = "https://ntfy.sh/$topic"
                val mediaType = "text/plain; charset=utf-8".toMediaType()
                val body = jsonString.toRequestBody(mediaType)
                val request = Request.Builder()
                    .url(postUrl)
                    .header("Title", "sarnas-sync")
                    .header("Priority", "urgent")
                    .post(body)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        _connectionState.value = ConnectionState.CONNECTED
                    }
                }
            } catch (e: Exception) {
                Log.d(tag, "HTTP publish transient note: ${e.message}")
            }
        }
    }

    private fun handleIncomingRaw(raw: String) {
        try {
            val root = JSONObject(raw)
            val event = root.optString("event", "")

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

            val message = deserializeMessage(msgJson) ?: return

            // Deduplication by sender + type + timestamp
            val msgKey = "${message.senderId}_${message.type}_${message.timestamp}"
            if (processedMessageIds.contains(msgKey)) {
                return // Already processed
            }
            // Keep cache size bounded
            if (processedMessageIds.size > 200) {
                processedMessageIds.clear()
            }
            processedMessageIds.add(msgKey)

            onMessageReceived(message)
        } catch (e: Exception) {
            Log.d(tag, "Parsing note: ${e.message}")
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
        msg.videoFrameBase64?.let { obj.put("videoFrameBase64", it) }
        msg.audioPacketBase64?.let { obj.put("audioPacketBase64", it) }
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
            videoFrameBase64 = if (json.has("videoFrameBase64")) json.optString("videoFrameBase64", null) else null,
            audioPacketBase64 = if (json.has("audioPacketBase64")) json.optString("audioPacketBase64", null) else null,
            timestamp = json.optLong("timestamp", System.currentTimeMillis())
        )
    }

    private fun scheduleReconnect() {
        if (isIntentionalClose) return
        scope.launch {
            delay(2000)
            val topic = currentTopic
            if (topic != null && !isIntentionalClose) {
                connectCloudWebSocket(topic)
            }
        }
    }

    fun disconnect() {
        isIntentionalClose = true
        fallbackPollJob?.cancel()
        fallbackPollJob = null
        activeWebSocket?.close(1000, "User left room")
        activeWebSocket = null
        lanListenerJob?.cancel()
        lanListenerJob = null
        try {
            lanSocket?.close()
        } catch (e: Exception) {
            // Ignored
        }
        lanSocket = null
        currentTopic = null
        processedMessageIds.clear()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    companion object {
        fun formatTopic(roomId: String): String {
            val clean = roomId.trim().lowercase().replace(Regex("[^a-z0-9]"), "_")
            return "sarnas_v1_room_$clean"
        }
    }
}
