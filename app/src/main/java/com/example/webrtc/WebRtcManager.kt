package com.example.webrtc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap

data class RemotePeerMedia(
    val peerId: String,
    val videoTrack: VideoTrack? = null,
    val audioTrack: AudioTrack? = null,
    val isConnected: Boolean = false
)

/**
 * Production-grade WebRTC Peer-to-Peer Media & Signaling Engine:
 * - Direct P2P Video and Audio transmission between phones across networks.
 * - Google STUN servers for NAT traversal (stun:stun.l.google.com:19302).
 * - Full hardware-accelerated video capture using Camera2Enumerator.
 * - Software & Hardware video codecs (VP8, VP9, H.264).
 * - Real-time track muting/unmuting and camera enable/disable.
 */
class WebRtcManager(
    private val context: Context,
    private val myUserId: String,
    private val sendSignal: (
        type: String,
        targetUserId: String,
        sdpType: String?,
        sdpDescription: String?,
        iceSdp: String?,
        iceSdpMid: String?,
        iceSdpMLineIndex: Int?
    ) -> Unit
) {
    private val tag = "WebRtcManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val rootEglBase: EglBase = EglBase.create()

    private var factory: PeerConnectionFactory? = null
    private var videoSource: VideoSource? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var audioSource: AudioSource? = null
    var localAudioTrack: AudioTrack? = null
        private set
    var localVideoTrack: VideoTrack? = null
        private set

    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()
    private val _remotePeers = MutableStateFlow<Map<String, RemotePeerMedia>>(emptyMap())
    val remotePeers: StateFlow<Map<String, RemotePeerMedia>> = _remotePeers.asStateFlow()

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.relay.metered.ca:80").createIceServer()
    )

    init {
        initializePeerConnectionFactory()
    }

    private fun initializePeerConnectionFactory() {
        try {
            val options = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)

            val encoderFactory = DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)

            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .setOptions(PeerConnectionFactory.Options())
                .createPeerConnectionFactory()

            Log.d(tag, "WebRTC PeerConnectionFactory initialized successfully.")
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize PeerConnectionFactory: ${e.message}", e)
        }
    }

    /**
     * Initializes local audio track with acoustic echo cancellation.
     */
    fun startLocalAudio(initialMuted: Boolean = false) {
        val f = factory ?: return
        if (localAudioTrack != null) return

        try {
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            }
            audioSource = f.createAudioSource(audioConstraints)
            val audioTrack = f.createAudioTrack("ARDAMSa0", audioSource)
            audioTrack.setEnabled(!initialMuted)
            localAudioTrack = audioTrack

            // Add audio track to any existing peer connections
            peerConnections.values.forEach { pc ->
                try {
                    pc.addTrack(audioTrack, listOf("ARDAMS"))
                } catch (e: Exception) {
                    Log.w(tag, "Failed to add audio track to pc: ${e.message}")
                }
            }
            Log.d(tag, "Local audio track created (muted=$initialMuted)")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start local audio: ${e.message}", e)
        }
    }

    /**
     * Initializes local video track from front camera.
     */
    fun startLocalVideo(initialCameraOn: Boolean = true) {
        val f = factory ?: return
        if (localVideoTrack != null) return

        try {
            val capturer = createCameraCapturer()
            if (capturer == null) {
                Log.w(tag, "No camera capturer found on device")
                return
            }
            videoCapturer = capturer

            surfaceTextureHelper = SurfaceTextureHelper.create("WebRtcCaptureThread", rootEglBase.eglBaseContext)
            val vSource = f.createVideoSource(capturer.isScreencast)
            videoSource = vSource

            capturer.initialize(surfaceTextureHelper, context, vSource.capturerObserver)
            if (initialCameraOn) {
                capturer.startCapture(480, 480, 24)
            }

            val vTrack = f.createVideoTrack("ARDAMSv0", vSource)
            vTrack.setEnabled(initialCameraOn)
            localVideoTrack = vTrack

            // Add video track to any existing peer connections
            peerConnections.values.forEach { pc ->
                try {
                    pc.addTrack(vTrack, listOf("ARDAMS"))
                } catch (e: Exception) {
                    Log.w(tag, "Failed to add video track to pc: ${e.message}")
                }
            }
            Log.d(tag, "Local video track created (cameraOn=$initialCameraOn)")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start local video: ${e.message}", e)
        }
    }

    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator: CameraEnumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        // Prefer front camera for watch room face bubble
        for (name in deviceNames) {
            if (enumerator.isFrontFacing(name)) {
                val capturer = enumerator.createCapturer(name, null)
                if (capturer != null) return capturer
            }
        }
        // Fallback to back camera
        for (name in deviceNames) {
            if (!enumerator.isFrontFacing(name)) {
                val capturer = enumerator.createCapturer(name, null)
                if (capturer != null) return capturer
            }
        }
        return null
    }

    fun setCameraEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
        try {
            if (enabled) {
                videoCapturer?.startCapture(480, 480, 24)
            } else {
                videoCapturer?.stopCapture()
            }
        } catch (e: Exception) {
            Log.w(tag, "Error toggling video capturer: ${e.message}")
        }
    }

    fun setMicrophoneMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    /**
     * Initiates WebRTC call with a remote peer (creates Offer).
     */
    fun initiateCallToPeer(peerId: String) {
        if (peerId == myUserId) return
        Log.d(tag, "Initiating WebRTC offer to peer: $peerId")
        val pc = getOrCreatePeerConnection(peerId)

        val mediaConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) return
                pc.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        Log.d(tag, "Local offer description set, sending to peer: $peerId")
                        sendSignal(
                            "WEBRTC_OFFER",
                            peerId,
                            sdp.type.canonicalForm(),
                            sdp.description,
                            null, null, null
                        )
                    }
                }, sdp)
            }
        }, mediaConstraints)
    }

    /**
     * Handles incoming WebRTC Offer from a remote peer.
     */
    fun handleRemoteOffer(senderId: String, sdpDescription: String) {
        if (senderId == myUserId) return
        Log.d(tag, "Handling incoming WebRTC offer from: $senderId")
        val pc = getOrCreatePeerConnection(senderId)

        val remoteSdp = SessionDescription(SessionDescription.Type.OFFER, sdpDescription)
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                Log.d(tag, "Remote offer set successfully, creating answer for: $senderId")
                val mediaConstraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                }
                pc.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(answerSdp: SessionDescription?) {
                        if (answerSdp == null) return
                        pc.setLocalDescription(object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                Log.d(tag, "Local answer set successfully, sending to peer: $senderId")
                                sendSignal(
                                    "WEBRTC_ANSWER",
                                    senderId,
                                    answerSdp.type.canonicalForm(),
                                    answerSdp.description,
                                    null, null, null
                                )
                            }
                        }, answerSdp)
                    }
                }, mediaConstraints)
            }
        }, remoteSdp)
    }

    /**
     * Handles incoming WebRTC Answer from remote peer.
     */
    fun handleRemoteAnswer(senderId: String, sdpDescription: String) {
        if (senderId == myUserId) return
        Log.d(tag, "Handling incoming WebRTC answer from: $senderId")
        val pc = peerConnections[senderId] ?: return
        val remoteSdp = SessionDescription(SessionDescription.Type.ANSWER, sdpDescription)
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                Log.d(tag, "Remote answer applied successfully for peer: $senderId")
            }
        }, remoteSdp)
    }

    /**
     * Handles incoming ICE Candidate from remote peer.
     */
    fun handleRemoteIceCandidate(
        senderId: String,
        sdp: String,
        sdpMid: String?,
        sdpMLineIndex: Int
    ) {
        if (senderId == myUserId) return
        val pc = peerConnections[senderId] ?: return
        val candidate = IceCandidate(sdpMid ?: "0", sdpMLineIndex, sdp)
        pc.addIceCandidate(candidate)
        Log.d(tag, "Added remote ICE candidate from: $senderId")
    }

    private fun getOrCreatePeerConnection(peerId: String): PeerConnection {
        return peerConnections.getOrPut(peerId) {
            createPeerConnection(peerId)
        }
    }

    private fun createPeerConnection(peerId: String): PeerConnection {
        val f = factory ?: throw IllegalStateException("PeerConnectionFactory not initialized")
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.d(tag, "Peer [$peerId] signaling state: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(tag, "Peer [$peerId] ICE state: $state")
                val isConnected = state == PeerConnection.IceConnectionState.CONNECTED ||
                        state == PeerConnection.IceConnectionState.COMPLETED
                scope.launch {
                    val currentMap = _remotePeers.value.toMutableMap()
                    val existing = currentMap[peerId] ?: RemotePeerMedia(peerId = peerId)
                    currentMap[peerId] = existing.copy(isConnected = isConnected)
                    _remotePeers.value = currentMap
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(tag, "Peer [$peerId] ICE gathering: $state")
            }

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null) return
                sendSignal(
                    "WEBRTC_ICE_CANDIDATE",
                    peerId,
                    null,
                    null,
                    candidate.sdp,
                    candidate.sdpMid,
                    candidate.sdpMLineIndex
                )
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onAddStream(stream: MediaStream?) {
                Log.d(tag, "Peer [$peerId] added media stream with ${stream?.videoTracks?.size} video, ${stream?.audioTracks?.size} audio")
                val vTrack = stream?.videoTracks?.firstOrNull()
                val aTrack = stream?.audioTracks?.firstOrNull()

                scope.launch {
                    val currentMap = _remotePeers.value.toMutableMap()
                    val existing = currentMap[peerId] ?: RemotePeerMedia(peerId = peerId)
                    currentMap[peerId] = existing.copy(
                        videoTrack = vTrack ?: existing.videoTrack,
                        audioTrack = aTrack ?: existing.audioTrack,
                        isConnected = true
                    )
                    _remotePeers.value = currentMap
                }
            }

            override fun onRemoveStream(stream: MediaStream?) {
                scope.launch {
                    val currentMap = _remotePeers.value.toMutableMap()
                    currentMap.remove(peerId)
                    _remotePeers.value = currentMap
                }
            }

            override fun onDataChannel(dataChannel: org.webrtc.DataChannel?) {}

            override fun onRenegotiationNeeded() {
                Log.d(tag, "Peer [$peerId] renegotiation needed")
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                val receiver: RtpReceiver? = transceiver?.receiver
                val track = receiver?.track()
                if (track is VideoTrack) {
                    scope.launch {
                        val currentMap = _remotePeers.value.toMutableMap()
                        val existing = currentMap[peerId] ?: RemotePeerMedia(peerId = peerId)
                        currentMap[peerId] = existing.copy(videoTrack = track, isConnected = true)
                        _remotePeers.value = currentMap
                    }
                } else if (track is AudioTrack) {
                    scope.launch {
                        val currentMap = _remotePeers.value.toMutableMap()
                        val existing = currentMap[peerId] ?: RemotePeerMedia(peerId = peerId)
                        currentMap[peerId] = existing.copy(audioTrack = track, isConnected = true)
                        _remotePeers.value = currentMap
                    }
                }
            }
        }

        val pc = f.createPeerConnection(rtcConfig, observer)
            ?: throw IllegalStateException("Failed to create PeerConnection")

        // Attach local tracks
        localAudioTrack?.let {
            try { pc.addTrack(it, listOf("ARDAMS")) } catch (e: Exception) {}
        }
        localVideoTrack?.let {
            try { pc.addTrack(it, listOf("ARDAMS")) } catch (e: Exception) {}
        }

        return pc
    }

    /**
     * Disconnects a specific peer when they leave the room.
     */
    fun removePeer(peerId: String) {
        val pc = peerConnections.remove(peerId)
        try {
            pc?.close()
        } catch (e: Exception) {}
        scope.launch {
            val currentMap = _remotePeers.value.toMutableMap()
            currentMap.remove(peerId)
            _remotePeers.value = currentMap
        }
    }

    /**
     * Disposes all WebRTC resources upon leaving the room.
     */
    fun close() {
        peerConnections.values.forEach { pc ->
            try { pc.close() } catch (e: Exception) {}
        }
        peerConnections.clear()
        _remotePeers.value = emptyMap()

        try { videoCapturer?.stopCapture() } catch (e: Exception) {}
        try { videoCapturer?.dispose() } catch (e: Exception) {}
        videoCapturer = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        videoSource?.dispose()
        videoSource = null

        audioSource?.dispose()
        audioSource = null

        localVideoTrack = null
        localAudioTrack = null

        try { factory?.dispose() } catch (e: Exception) {}
        factory = null

        try { rootEglBase.release() } catch (e: Exception) {}
        Log.d(tag, "WebRTC resources fully closed and released.")
    }

    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {
            Log.e("WebRtcManager", "SdpObserver onCreateFailure: $error")
        }
        override fun onSetFailure(error: String?) {
            Log.e("WebRtcManager", "SdpObserver onSetFailure: $error")
        }
    }
}
