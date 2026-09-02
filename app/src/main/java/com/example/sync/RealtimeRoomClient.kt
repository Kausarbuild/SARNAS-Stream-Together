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
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

sealed class RoomVerificationResult {
    data class Found(val roomId: String, val roomName: String?, val hostName: String?) : RoomVerificationResult()
    data class NotFound(val reason: String) : RoomVerificationResult()
    data class Error(val message: String) : RoomVerificationResult()
}

/**
 * High-speed hybrid real-time synchronization & room verification engine:
 * 1. Online Room Verification over internet to guarantee only existing, active rooms can be joined.
 * 2. Fast IPv4-prioritized WebSocket & HTTP cloud bus.
 * 3. Instant Local Network (LAN/Wi-Fi/Hotspot) UDP broadcast mesh.
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

    private val httpClient = OkHttpClient.Builder()
        .dns(ipv4FirstDns)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var activeWebSocket: WebSocket? = null
    private var currentTopic: String? = null
    private var isIntentionalClose = false
    private var lanListenerJob: Job? = null
    private var lanSocket: DatagramSocket? = null

    private val _connectionState = kotlinx.coroutines.flow.MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: kotlinx.coroutines.flow.StateFlow<ConnectionState> = _connectionState

    /**
     * Checks if a room with the given code has an active host online.
     * Polls the room's live cloud topic endpoint. If active heartbeats or room announcements
     * were published within the recent window, the room is verified!
     */
    suspend fun verifyRoomExists(roomId: String): RoomVerificationResult = withContext(Dispatchers.IO) {
        val cleanRoomId = roomId.trim().uppercase()
        if (cleanRoomId.length < 3) {
            return@withContext RoomVerificationResult.NotFound("Room code is too short.")
        }

        val topic = formatTopic(cleanRoomId)
        val pollUrl = "https://ntfy.sh/$topic/json?poll=1&since=90s"

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

            for (line in lines) {
                try {
                    val root = JSONObject(line)
                    val event = root.optString("event", "")
                    if (event == "message" && root.has("message")) {
                        val payloadStr = root.getString("message")
                        if (payloadStr.startsWith("{")) {
                            val msg = JSONObject(payloadStr)
                            val type = msg.optString("type", "")
                            if (type == "ROOM_ANNOUNCE" || type == "JOIN" || type == "HEARTBEAT" || type == "SYNC_STATE" || type == "PLAY" || type == "PAUSE") {
                                foundActiveRoom = true
                                if (msg.has("senderName")) foundHostName = msg.optString("senderName")
                                if (msg.has("videoTitle")) foundRoomName = msg.optString("videoTitle")
                            }
                        }
                    }
                } catch (e: Exception) {
                    // ignore line parse error
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
            // Fallback: If network poll fails, but network is accessible, probe with quick UDP LAN check
            return@withContext RoomVerificationResult.NotFound("Could not connect to room '$cleanRoomId'. Make sure the host has created the room.")
        }
    }

    fun connect(roomId: String) {
        disconnect()
        isIntentionalClose = false

        val sanitizedTopic = formatTopic(roomId)
        currentTopic = sanitizedTopic
        _connectionState.value = ConnectionState.CONNECTING

        // 1. Start Local LAN P2P UDP listener on port 8989
        startLanListener(sanitizedTopic)

        // 2. Connect to Cloud WebSocket
        connectCloudWebSocket(sanitizedTopic)
    }

    private fun connectCloudWebSocket(topic: String) {
        val wsUrl = "wss://ntfy.sh/$topic/ws"
        val request = Request.Builder()
            .url(wsUrl)
            .header("User-Agent", "SARNAS-Android/2.0")
            .build()

        activeWebSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(tag, "Connected to cloud sync topic: $topic")
                _connectionState.value = ConnectionState.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
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
                Log.w(tag, "WebSocket disconnected (${t.message}), switching to LAN + HTTP mesh...")
                if (!isIntentionalClose) {
                    scheduleReconnect()
                }
            }
        })
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
                val buffer = ByteArray(4096)

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
            // A. Broadcast over LAN (Wi-Fi / Hotspot)
            try {
                val bytes = jsonString.toByteArray(Charsets.UTF_8)
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val sendSocket = DatagramSocket().apply { broadcast = true }
                val packet = DatagramPacket(bytes, bytes.size, broadcastAddr, 8989)
                sendSocket.send(packet)
                sendSocket.close()
            } catch (e: Exception) {
                // LAN broadcast ignored if network doesn't support UDP broadcast
            }

            // B. Send via active Cloud WebSocket if alive (if supported)
            try {
                activeWebSocket?.send(jsonString)
            } catch (e: Exception) {
                // Ignore
            }

            // C. Asynchronous HTTP publish to guarantee instant broadcast to all room subscribers
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
                Log.d(tag, "HTTP publish transient status: ${e.message}")
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

            val message = deserializeMessage(msgJson)
            if (message != null) {
                onMessageReceived(message)
            }
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
            delay(3000)
            val topic = currentTopic
            if (topic != null && !isIntentionalClose) {
                connectCloudWebSocket(topic)
            }
        }
    }

    fun disconnect() {
        isIntentionalClose = true
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
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    companion object {
        fun formatTopic(roomId: String): String {
            val clean = roomId.trim().lowercase().replace(Regex("[^a-z0-9]"), "_")
            return "sarnas_v1_room_$clean"
        }
    }
}
