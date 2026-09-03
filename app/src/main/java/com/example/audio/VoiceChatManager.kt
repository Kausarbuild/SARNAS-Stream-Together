package com.example.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Real-time bidirectional voice chat engine:
 * 1. Captures microphone audio using AudioRecord (8000 Hz, 16-bit PCM).
 * 2. Compresses using G.711 mu-law (1 byte per sample = 8000 bytes/sec).
 * 3. Uses Voice Activity Detection (VAD) to suppress silence and reduce bandwidth.
 * 4. Transmits audio over local UDP broadcast (port 8992) for zero-latency LAN/Hotspot,
 *    and delivers packets through the real-time cloud transport for internet rooms.
 * 5. Plays received peer audio in real-time using streaming AudioTrack.
 */
class VoiceChatManager(
    private val context: Context? = null,
    private val onAudioPacketReady: (ByteArray) -> Unit
) {
    private val tag = "VoiceChatManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val sampleRate = 8000
    private val channelInConfig = AudioFormat.CHANNEL_IN_MONO
    private val channelOutConfig = AudioFormat.CHANNEL_OUT_MONO
    private val audioEncoding = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var recordJob: Job? = null
    private var udpListenJob: Job? = null
    private var udpSocket: DatagramSocket? = null

    @Volatile
    private var isRecording = false

    @Volatile
    private var isMuted = false

    private val vadSilenceThreshold = 250 // RMS threshold for voice activity

    init {
        initAudioTrack()
        startUdpListener()
    }

    private fun initAudioTrack() {
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelOutConfig, audioEncoding)
            val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioEncoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelOutConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
        } catch (e: Exception) {
            Log.e(tag, "AudioTrack init error", e)
        }
    }

    private fun startUdpListener() {
        udpListenJob?.cancel()
        udpListenJob = scope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(8992).apply {
                    broadcast = true
                    reuseAddress = true
                    soTimeout = 2000
                }
                udpSocket = socket
                val buffer = ByteArray(2048)

                val myLocalAddrs = java.net.NetworkInterface.getNetworkInterfaces().toList()
                    .flatMap { it.inetAddresses.toList() }
                    .map { it.hostAddress }
                    .toSet()

                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val senderHost = packet.address.hostAddress
                        // CRITICAL: Filter out own packets to prevent microphone echo/feedback loop
                        if (senderHost != null && (myLocalAddrs.contains(senderHost) || packet.address.isLoopbackAddress)) {
                            continue
                        }

                        if (packet.length > 0) {
                            val audioData = ByteArray(packet.length)
                            System.arraycopy(packet.data, 0, audioData, 0, packet.length)
                            playMuLawAudio(audioData)
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // socket timeout, loop
                    } catch (e: Exception) {
                        if (!isActive) break
                    }
                }
            } catch (e: Exception) {
                Log.d(tag, "UDP Audio socket note: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (context != null && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(tag, "Record audio permission not granted; skipping voice chat recording")
            return
        }
        if (isRecording) return
        isRecording = true
        isMuted = false

        recordJob?.cancel()
        recordJob = scope.launch(Dispatchers.IO) {
            try {
                val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelInConfig, audioEncoding)
                val bufferSize = (minBufferSize * 2).coerceAtLeast(1600) // 100ms chunks = 800 shorts = 1600 bytes
                val chunkSamples = 800 // 100ms at 8000Hz

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    channelInConfig,
                    audioEncoding,
                    bufferSize
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(tag, "AudioRecord failed to initialize")
                    audioRecord?.release()
                    audioRecord = null
                    return@launch
                }

                audioRecord?.startRecording()
                val pcmBuffer = ShortArray(chunkSamples)

                while (isActive && isRecording) {
                    if (isMuted) {
                        kotlinx.coroutines.delay(100)
                        continue
                    }

                    val samplesRead = audioRecord?.read(pcmBuffer, 0, chunkSamples) ?: -1
                    if (samplesRead > 0) {
                        // Calculate RMS for Voice Activity Detection
                        var sum = 0.0
                        for (i in 0 until samplesRead) {
                            val s = pcmBuffer[i].toDouble()
                            sum += s * s
                        }
                        val rms = Math.sqrt(sum / samplesRead)

                        // Transmit if voice detected above threshold
                        if (rms >= vadSilenceThreshold) {
                            val muLawBuffer = ByteArray(samplesRead)
                            for (i in 0 until samplesRead) {
                                muLawBuffer[i] = linearToMuLaw(pcmBuffer[i])
                            }

                            // 1. Send via local LAN UDP broadcast
                            broadcastUdpAudio(muLawBuffer)

                            // 2. Deliver to cloud network callback
                            onAudioPacketReady(muLawBuffer)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "AudioRecord loop exception", e)
            } finally {
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (e: Exception) {
                    // Ignore
                }
                audioRecord = null
            }
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
    }

    fun stopRecording() {
        isRecording = false
        recordJob?.cancel()
        recordJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            // Ignore
        }
        audioRecord = null
    }

    private fun broadcastUdpAudio(audioBytes: ByteArray) {
        try {
            val broadcastAddr = InetAddress.getByName("255.255.255.255")
            val sendSocket = DatagramSocket().apply { broadcast = true }
            val packet = DatagramPacket(audioBytes, audioBytes.size, broadcastAddr, 8992)
            sendSocket.send(packet)
            sendSocket.close()
        } catch (e: Exception) {
            // UDP broadcast failure on some networks is normal
        }
    }

    /**
     * Plays received G.711 mu-law audio chunk from a peer.
     */
    fun playMuLawAudio(muLawBytes: ByteArray) {
        if (muLawBytes.isEmpty()) return
        val pcmOut = ShortArray(muLawBytes.size)
        for (i in muLawBytes.indices) {
            pcmOut[i] = muLawToLinear(muLawBytes[i])
        }

        try {
            if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack?.play()
            }
            audioTrack?.write(pcmOut, 0, pcmOut.size)
        } catch (e: Exception) {
            Log.d(tag, "Audio write note: ${e.message}")
        }
    }

    fun release() {
        stopRecording()
        udpListenJob?.cancel()
        udpListenJob = null
        try {
            udpSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        udpSocket = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            // Ignore
        }
        audioTrack = null
    }

    companion object {
        private const val BIAS = 0x84
        private const val CLIP = 32635

        fun linearToMuLaw(pcm16: Short): Byte {
            var sample = pcm16.toInt()
            var sign = 0
            if (sample < 0) {
                sample = -sample
                sign = 0x80
            }
            if (sample > CLIP) sample = CLIP
            sample += BIAS

            var exponent = 7
            var expMask = 0x4000
            while ((sample and expMask) == 0 && exponent > 0) {
                exponent--
                expMask = expMask shr 1
            }
            val mantissa = (sample shr (exponent + 3)) and 0x0F
            val muLaw = (sign or (exponent shl 4) or mantissa).inv()
            return (muLaw and 0xFF).toByte()
        }

        fun muLawToLinear(muLawByte: Byte): Short {
            val muLaw = (muLawByte.toInt() and 0xFF).inv()
            val sign = muLaw and 0x80
            val exponent = (muLaw shr 4) and 0x07
            val mantissa = muLaw and 0x0F
            var sample = ((mantissa shl 3) + BIAS) shl exponent
            sample -= BIAS
            return (if (sign != 0) -sample else sample).toShort()
        }
    }
}
