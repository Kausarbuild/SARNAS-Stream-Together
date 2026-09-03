package com.example.sync

import android.content.Context
import android.util.Log
import com.example.data.Friend
import com.example.data.FriendRequest
import com.example.data.RoomInvite
import com.example.data.UserProfile
import com.example.data.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real-time Friend System & User Registry Client over MQTT.
 * Manages:
 * 1. Publishing user profiles with retain to `sarnas/v2/registry/users/<username>`.
 * 2. Querying the registry for real usernames (returns "User not found" if missing).
 * 3. Sending and receiving persistent friend requests (pending, accepted, declined).
 * 4. Delivering real-time room invitations to friends.
 */
class FriendSyncClient private constructor(
    private val context: Context,
    private val userRepository: UserRepository
) {
    private val tag = "FriendSyncClient"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val brokerHost = "broker.emqx.io"
    private val brokerPort = 1883

    private var activeSocket: Socket? = null
    private var outputStream: OutputStream? = null
    private var connectionJob: Job? = null
    private var pingJob: Job? = null
    private val packetIdCounter = AtomicInteger(1)

    private val searchResults = ConcurrentHashMap<String, UserProfile?>()
    private val _incomingInvites = MutableSharedFlow<RoomInvite>(extraBufferCapacity = 16)
    val incomingInvites: SharedFlow<RoomInvite> = _incomingInvites.asSharedFlow()

    private var currentProfile: UserProfile? = null

    fun start(profile: UserProfile) {
        currentProfile = profile
        connectionJob?.cancel()
        connectionJob = scope.launch {
            connectLoop(profile)
        }
    }

    private suspend fun connectLoop(profile: UserProfile) {
        while (scope.isActive) {
            try {
                Log.d(tag, "Connecting to MQTT for friend sync (${profile.username})...")
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.soTimeout = 60000
                socket.connect(InetSocketAddress(brokerHost, brokerPort), 6000)
                activeSocket = socket
                outputStream = socket.getOutputStream()

                val clientId = "sarnas_user_${profile.id}_${UUID.randomUUID().toString().take(6)}"
                val connectPacket = encodeConnect(clientId)
                outputStream?.write(connectPacket)
                outputStream?.flush()

                val input = socket.getInputStream()
                val connAckHeader = input.read()
                if (connAckHeader == 0x20) {
                    val remLen = readRemainingLength(input)
                    val connAckPayload = readExact(input, remLen)
                    if (connAckPayload.size >= 2 && connAckPayload[1].toInt() == 0) {
                        Log.d(tag, "FriendSyncClient connected.")

                        // Subscribe to our personal inbox
                        val inboxTopic = "sarnas/v2/inbox/${profile.id}/#"
                        subscribeTo(inboxTopic)

                        // Register our profile to the public registry with retain=true
                        publishProfileRegistry(profile)

                        // Start KeepAlive PING loop
                        startPingLoop()

                        // Process incoming MQTT stream
                        readIncomingStream(input)
                    }
                }
            } catch (e: Exception) {
                Log.d(tag, "FriendSyncClient connection note: ${e.message}")
            } finally {
                cleanupSocket()
            }
            delay(5000)
        }
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(30000)
                try {
                    outputStream?.write(byteArrayOf(0xC0.toByte(), 0x00))
                    outputStream?.flush()
                } catch (e: Exception) {
                    break
                }
            }
        }
    }

    private fun cleanupSocket() {
        pingJob?.cancel()
        pingJob = null
        outputStream = null
        try {
            activeSocket?.close()
        } catch (e: Exception) {}
        activeSocket = null
    }

    private fun subscribeTo(topic: String) {
        try {
            val packet = encodeSubscribe(topic, nextPacketId())
            outputStream?.write(packet)
            outputStream?.flush()
        } catch (e: Exception) {
            Log.w(tag, "Failed to subscribe to $topic: ${e.message}")
        }
    }

    /**
     * Publishes our UserProfile to the cloud registry so other users can search for us.
     */
    fun publishProfileRegistry(profile: UserProfile) {
        currentProfile = profile
        scope.launch {
            try {
                val cleanUsername = profile.username.trim().lowercase()
                if (cleanUsername.isBlank()) return@launch

                val topic = "sarnas/v2/registry/users/$cleanUsername"
                val json = JSONObject().apply {
                    put("uid", profile.id)
                    put("username", cleanUsername)
                    put("displayName", profile.name)
                    put("avatarUri", profile.avatarUri)
                    put("avatarColorHex", profile.avatarColorHex)
                    put("createdAt", profile.createdAt)
                }

                val payload = json.toString().toByteArray(Charsets.UTF_8)
                val publishPacket = encodePublish(topic, payload, retain = true)
                outputStream?.write(publishPacket)
                outputStream?.flush()
                Log.d(tag, "Published user profile registry for '$cleanUsername'")
            } catch (e: Exception) {
                Log.e(tag, "Error publishing profile registry: ${e.message}")
            }
        }
    }

    /**
     * Unregisters an old username from the cloud registry so it is no longer retained.
     */
    fun unregisterUsername(oldUsername: String) {
        scope.launch {
            try {
                val clean = oldUsername.trim().lowercase()
                if (clean.isBlank()) return@launch
                val topic = "sarnas/v2/registry/users/$clean"
                val emptyPublish = encodePublish(topic, ByteArray(0), retain = true)
                outputStream?.write(emptyPublish)
                outputStream?.flush()
                Log.d(tag, "Unregistered old username '$clean'")
            } catch (e: Exception) {
                Log.e(tag, "Error unregistering username: ${e.message}")
            }
        }
    }

    /**
     * Queries the actual backend registry for a username.
     * Returns the matching UserProfile, or null if the user does NOT exist.
     */
    suspend fun searchUserByUsername(targetUsername: String): UserProfile? = withContext(Dispatchers.IO) {
        val clean = targetUsername.trim().lowercase()
        if (clean.isBlank()) return@withContext null

        val topic = "sarnas/v2/registry/users/$clean"
        searchResults.remove(clean)

        // Subscribe to query topic
        subscribeTo(topic)

        // Wait up to 1800ms for response from retained message
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 1800L) {
            if (searchResults.containsKey(clean)) {
                return@withContext searchResults[clean]
            }
            delay(100)
        }

        // Not found
        return@withContext null
    }

    /**
     * Sends a real Friend Request to the recipient user.
     */
    suspend fun sendFriendRequest(recipient: UserProfile): Result<String> = withContext(Dispatchers.IO) {
        val me = currentProfile ?: return@withContext Result.failure(IllegalStateException("Local profile not set"))

        if (recipient.id == me.id || recipient.username.equals(me.username, ignoreCase = true)) {
            return@withContext Result.failure(IllegalArgumentException("You cannot send a friend request to yourself"))
        }

        if (userRepository.isFriend(recipient.id)) {
            return@withContext Result.failure(IllegalStateException("Already in your friends list"))
        }

        if (userRepository.hasPendingRequest(recipient.id, me.id)) {
            return@withContext Result.failure(IllegalStateException("Friend request already sent"))
        }

        try {
            val requestId = "req_" + UUID.randomUUID().toString().take(8)
            val json = JSONObject().apply {
                put("type", "FRIEND_REQUEST")
                put("requestId", requestId)
                put("requesterUid", me.id)
                put("requesterUsername", me.username)
                put("requesterDisplayName", me.name)
                put("requesterAvatarUri", me.avatarUri)
                put("requesterAvatarColorHex", me.avatarColorHex)
                put("recipientUid", recipient.id)
                put("recipientUsername", recipient.username)
                put("createdAt", System.currentTimeMillis())
            }

            val topic = "sarnas/v2/inbox/${recipient.id}/requests"
            val payload = json.toString().toByteArray(Charsets.UTF_8)
            val packet = encodePublish(topic, payload, retain = false)
            outputStream?.write(packet)
            outputStream?.flush()

            // Save outgoing request locally
            val localRequest = FriendRequest(
                requestId = requestId,
                requesterUid = me.id,
                requesterUsername = me.username,
                requesterDisplayName = me.name,
                requesterAvatarUri = me.avatarUri,
                requesterAvatarColorHex = me.avatarColorHex,
                recipientUid = recipient.id,
                recipientUsername = recipient.username,
                status = "pending",
                createdAt = System.currentTimeMillis()
            )
            userRepository.saveFriendRequest(localRequest)

            return@withContext Result.success("Friend request sent to @${recipient.username}")
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    /**
     * Accepts a pending friend request and notifies the requester.
     */
    suspend fun acceptFriendRequest(request: FriendRequest) = withContext(Dispatchers.IO) {
        val me = currentProfile ?: return@withContext

        // 1. Add requester to our local friends table
        userRepository.addFriend(
            id = request.requesterUid,
            username = request.requesterUsername,
            name = request.requesterDisplayName,
            avatarUri = request.requesterAvatarUri,
            colorHex = request.requesterAvatarColorHex
        )

        // 2. Mark request accepted
        userRepository.updateFriendRequestStatus(request.requestId, "accepted")

        // 3. Notify the requester so they add us back
        try {
            val json = JSONObject().apply {
                put("type", "FRIEND_ACCEPT")
                put("requestId", request.requestId)
                put("friendUid", me.id)
                put("friendUsername", me.username)
                put("friendDisplayName", me.name)
                put("friendAvatarUri", me.avatarUri)
                put("friendAvatarColorHex", me.avatarColorHex)
                put("timestamp", System.currentTimeMillis())
            }
            val topic = "sarnas/v2/inbox/${request.requesterUid}/requests"
            val packet = encodePublish(topic, json.toString().toByteArray(Charsets.UTF_8), retain = false)
            outputStream?.write(packet)
            outputStream?.flush()
        } catch (e: Exception) {
            Log.e(tag, "Error sending friend accept notification: ${e.message}")
        }
    }

    /**
     * Declines a pending friend request.
     */
    suspend fun declineFriendRequest(requestId: String) = withContext(Dispatchers.IO) {
        userRepository.updateFriendRequestStatus(requestId, "declined")
        userRepository.deleteFriendRequest(requestId)
    }

    /**
     * Sends a real room invite to a friend.
     */
    suspend fun sendRoomInvite(friend: Friend, roomId: String, roomTitle: String) = withContext(Dispatchers.IO) {
        val me = currentProfile ?: return@withContext
        try {
            val json = JSONObject().apply {
                put("type", "ROOM_INVITE")
                put("inviteId", "inv_" + UUID.randomUUID().toString().take(6))
                put("roomId", roomId)
                put("roomTitle", roomTitle)
                put("senderUid", me.id)
                put("senderName", me.name)
                put("senderAvatarColorHex", me.avatarColorHex)
                put("timestamp", System.currentTimeMillis())
            }
            val topic = "sarnas/v2/inbox/${friend.id}/invites"
            val packet = encodePublish(topic, json.toString().toByteArray(Charsets.UTF_8), retain = false)
            outputStream?.write(packet)
            outputStream?.flush()
            Log.d(tag, "Sent room invite for $roomId to friend @${friend.username}")
        } catch (e: Exception) {
            Log.e(tag, "Error sending room invite: ${e.message}")
        }
    }

    private fun readIncomingStream(input: InputStream) {
        while (scope.isActive) {
            val header = input.read()
            if (header == -1) throw EOFException("Socket closed")
            val packetType = header and 0xF0

            when (packetType) {
                0x30 -> { // PUBLISH
                    val remLen = readRemainingLength(input)
                    val topLenHigh = input.read()
                    val topLenLow = input.read()
                    if (topLenHigh == -1 || topLenLow == -1) throw EOFException("Truncated topic len")
                    val topLen = (topLenHigh shl 8) or topLenLow
                    val topBytes = readExact(input, topLen)
                    val topic = String(topBytes, Charsets.UTF_8)

                    val payloadLen = remLen - (2 + topLen)
                    val payloadBytes = if (payloadLen > 0) readExact(input, payloadLen) else ByteArray(0)
                    val payloadStr = String(payloadBytes, Charsets.UTF_8)

                    handleIncomingMessage(topic, payloadStr)
                }
                0xD0 -> { // PINGRESP
                    val remLen = readRemainingLength(input)
                    if (remLen > 0) readExact(input, remLen)
                }
                0x90 -> { // SUBACK
                    val remLen = readRemainingLength(input)
                    if (remLen > 0) readExact(input, remLen)
                }
                else -> {
                    val remLen = readRemainingLength(input)
                    if (remLen > 0) readExact(input, remLen)
                }
            }
        }
    }

    private fun handleIncomingMessage(topic: String, payload: String) {
        try {
            if (payload.isBlank()) return
            val json = JSONObject(payload)

            if (topic.startsWith("sarnas/v2/registry/users/")) {
                val username = topic.removePrefix("sarnas/v2/registry/users/")
                val profile = UserProfile(
                    id = json.optString("uid", ""),
                    username = json.optString("username", username),
                    name = json.optString("displayName", "User"),
                    avatarUri = if (json.has("avatarUri")) json.optString("avatarUri", null) else null,
                    avatarColorHex = json.optString("avatarColorHex", "#E5A93C"),
                    createdAt = json.optLong("createdAt", System.currentTimeMillis())
                )
                searchResults[username] = profile
                return
            }

            val type = json.optString("type", "")
            when (type) {
                "FRIEND_REQUEST" -> {
                    val request = FriendRequest(
                        requestId = json.optString("requestId", UUID.randomUUID().toString()),
                        requesterUid = json.optString("requesterUid", ""),
                        requesterUsername = json.optString("requesterUsername", ""),
                        requesterDisplayName = json.optString("requesterDisplayName", "User"),
                        requesterAvatarUri = if (json.has("requesterAvatarUri")) json.optString("requesterAvatarUri", null) else null,
                        requesterAvatarColorHex = json.optString("requesterAvatarColorHex", "#4E95FF"),
                        recipientUid = json.optString("recipientUid", ""),
                        recipientUsername = json.optString("recipientUsername", ""),
                        status = "pending",
                        createdAt = json.optLong("createdAt", System.currentTimeMillis())
                    )
                    scope.launch {
                        userRepository.saveFriendRequest(request)
                    }
                }

                "FRIEND_ACCEPT" -> {
                    val friendUid = json.optString("friendUid", "")
                    val friendUsername = json.optString("friendUsername", "")
                    val friendName = json.optString("friendDisplayName", "Friend")
                    val friendColor = json.optString("friendAvatarColorHex", "#4E95FF")
                    val friendAvatar = if (json.has("friendAvatarUri")) json.optString("friendAvatarUri", null) else null

                    if (friendUid.isNotBlank()) {
                        scope.launch {
                            userRepository.addFriend(
                                id = friendUid,
                                username = friendUsername,
                                name = friendName,
                                avatarUri = friendAvatar,
                                colorHex = friendColor
                            )
                        }
                    }
                }

                "ROOM_INVITE" -> {
                    val invite = RoomInvite(
                        inviteId = json.optString("inviteId", UUID.randomUUID().toString()),
                        roomId = json.optString("roomId", ""),
                        roomTitle = json.optString("roomTitle", "Watch Room"),
                        senderUid = json.optString("senderUid", ""),
                        senderName = json.optString("senderName", "Friend"),
                        senderAvatarColorHex = json.optString("senderAvatarColorHex", "#E5A93C"),
                        timestamp = json.optLong("timestamp", System.currentTimeMillis())
                    )
                    _incomingInvites.tryEmit(invite)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error handling message on $topic: ${e.message}")
        }
    }

    private fun nextPacketId(): Int {
        val id = packetIdCounter.incrementAndGet()
        if (id > 65530) packetIdCounter.set(1)
        return id
    }

    private fun encodeConnect(clientId: String): ByteArray {
        val cidBytes = clientId.toByteArray(Charsets.UTF_8)
        val varHeader = byteArrayOf(
            0x00, 0x04, 'M'.code.toByte(), 'Q'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(),
            0x04, 0x02, 0x00, 0x3C
        )
        val payload = ByteArray(2 + cidBytes.size)
        payload[0] = (cidBytes.size shr 8).toByte()
        payload[1] = (cidBytes.size and 0xFF).toByte()
        System.arraycopy(cidBytes, 0, payload, 2, cidBytes.size)

        val remLen = varHeader.size + payload.size
        val out = ByteArrayOutputStream()
        out.write(0x10)
        writeRemainingLength(out, remLen)
        out.write(varHeader)
        out.write(payload)
        return out.toByteArray()
    }

    private fun encodeSubscribe(topic: String, packetId: Int): ByteArray {
        val topBytes = topic.toByteArray(Charsets.UTF_8)
        val varHeader = byteArrayOf((packetId shr 8).toByte(), (packetId and 0xFF).toByte())
        val payload = ByteArray(2 + topBytes.size + 1)
        payload[0] = (topBytes.size shr 8).toByte()
        payload[1] = (topBytes.size and 0xFF).toByte()
        System.arraycopy(topBytes, 0, payload, 2, topBytes.size)
        payload[payload.size - 1] = 0x00

        val remLen = varHeader.size + payload.size
        val out = ByteArrayOutputStream()
        out.write(0x82)
        writeRemainingLength(out, remLen)
        out.write(varHeader)
        out.write(payload)
        return out.toByteArray()
    }

    private fun encodePublish(topic: String, payload: ByteArray, retain: Boolean): ByteArray {
        val topBytes = topic.toByteArray(Charsets.UTF_8)
        val varHeader = ByteArray(2 + topBytes.size)
        varHeader[0] = (topBytes.size shr 8).toByte()
        varHeader[1] = (topBytes.size and 0xFF).toByte()
        System.arraycopy(topBytes, 0, varHeader, 2, topBytes.size)

        val remLen = varHeader.size + payload.size
        val out = ByteArrayOutputStream()
        val headerByte = if (retain) 0x31 else 0x30
        out.write(headerByte)
        writeRemainingLength(out, remLen)
        out.write(varHeader)
        out.write(payload)
        return out.toByteArray()
    }

    private fun writeRemainingLength(out: ByteArrayOutputStream, len: Int) {
        var x = len
        do {
            var encodedByte = x % 128
            x /= 128
            if (x > 0) encodedByte = encodedByte or 128
            out.write(encodedByte)
        } while (x > 0)
    }

    private fun readRemainingLength(input: InputStream): Int {
        var multiplier = 1
        var value = 0
        do {
            val b = input.read()
            if (b == -1) throw EOFException("Socket closed while reading length")
            value += (b and 0x7F) * multiplier
            multiplier *= 128
            if (multiplier > 128 * 128 * 128) throw IOException("Malformed MQTT length")
        } while ((b and 0x80) != 0)
        return value
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var totalRead = 0
        while (totalRead < count) {
            val read = input.read(buffer, totalRead, count - totalRead)
            if (read == -1) throw EOFException("Socket closed by peer")
            totalRead += read
        }
        return buffer
    }

    companion object {
        @Volatile
        private var INSTANCE: FriendSyncClient? = null

        fun getInstance(context: Context): FriendSyncClient {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FriendSyncClient(
                    context.applicationContext,
                    UserRepository.getInstance(context)
                ).also { INSTANCE = it }
            }
        }
    }
}
