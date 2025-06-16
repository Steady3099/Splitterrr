package com.example.splitterrr.utils.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import org.webrtc.*

class WebRtcClient(
    private val context: Context,
    private val mediaProjectionIntent: Intent,
    private val signalingClient: SignalingClient,
    private val eglBase: EglBase,
    private val localRenderer: SurfaceViewRenderer,
    private val remoteRenderer: SurfaceViewRenderer,
    private val listener: Listener
) {

    interface Listener {
        fun onPeerConnected()
        fun onPeerDisconnected()
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "WebRtcClient"
    }

    private val peerFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null

    init {
        Log.d(TAG, "init: Initializing PeerConnectionFactory.")
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        peerFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()
        Log.d(TAG, "init: PeerConnectionFactory created successfully.")
    }

    fun start() {
        Log.d(TAG, "start: Setting up signaling client callbacks.")
        signalingClient.onOfferReceived = { sdp ->
            Log.d(TAG, "onOfferReceived: Received Offer. Type: ${sdp.type}, SDP: ${sdp.description.take(100)}...")
            createPeerConnection()
            peerConnection?.setRemoteDescription(SimpleSdpObserver().also {
                Log.d(TAG, "setRemoteDescription: Setting remote description for received offer.")
            }, sdp)
            peerConnection?.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(answer: SessionDescription?) {
                    answer?.let {
                        Log.d(TAG, "onCreateSuccess: Created answer. Type: ${it.type}, SDP: ${it.description.take(100)}...")
                        peerConnection?.setLocalDescription(SimpleSdpObserver().also {
                            Log.d(TAG, "setLocalDescription: Setting local description for answer.")
                        }, it)
                        signalingClient.sendAnswer(it)
                        Log.d(TAG, "onCreateSuccess: Sent answer via signaling client.")
                        listener.onPeerConnected()
                    } ?: run {
                        val msg = "onCreateSuccess: Answer is null."
                        Log.e(TAG, msg)
                        listener.onError(msg)
                    }
                }
                override fun onSetSuccess() {
                    Log.d(TAG, "onSetSuccess: Set local description for answer succeeded.")
                }
                override fun onSetFailure(msg: String?) {
                    Log.e(TAG, "onSetFailure: Set local description for answer failed: $msg")
                    listener.onError("Set failed: $msg")
                }
                override fun onCreateFailure(msg: String?) {
                    Log.e(TAG, "onCreateFailure: Create answer failed: $msg")
                    listener.onError("Answer failed: $msg")
                }
            }, MediaConstraints())
        }

        signalingClient.onIceCandidateReceived = { candidate ->
            Log.d(TAG, "onIceCandidateReceived: Adding ICE candidate. SdpMid: ${candidate.sdpMid}, SdpMLineIndex: ${candidate.sdpMLineIndex}")
            peerConnection?.addIceCandidate(candidate)
        }

        signalingClient.onError = { err ->
            Log.e(TAG, "Signaling client reported error: $err")
            listener.onError("Signaling error: $err")
        }
    }

    fun initiateCall() {
        Log.d(TAG, "initiateCall: Starting WebRTC call initiation.")
        createPeerConnection()
        setupLocalVideo() // This adds the track to the peer connection
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(offer: SessionDescription?) {
                offer?.let {
                    Log.d(TAG, "onCreateSuccess: Created offer. Type: ${it.type}, SDP: ${it.description.take(100)}...")
                    peerConnection?.setLocalDescription(SimpleSdpObserver().also {
                        Log.d(TAG, "setLocalDescription: Setting local description for offer.")
                    }, it)
                    signalingClient.sendOffer(it)
                    Log.d(TAG, "onCreateSuccess: Sent offer via signaling client.")
                    // listener.onPeerConnected() // This might be premature, usually called after answer/ICE complete
                    // However, for initiator, this signals the start of the call flow.
                    listener.onPeerConnected()
                } ?: run {
                    val msg = "onCreateSuccess: Offer is null."
                    Log.e(TAG, msg)
                    listener.onError(msg)
                }
            }
            override fun onSetSuccess() {
                Log.d(TAG, "onSetSuccess: Set local description for offer succeeded.")
            }
            override fun onCreateFailure(msg: String?) {
                Log.e(TAG, "onCreateFailure: Create offer failed: $msg")
                listener.onError("Offer failed: $msg")
            }
            override fun onSetFailure(msg: String?) {
                Log.e(TAG, "onSetFailure: Set local description for offer failed: $msg")
                listener.onError("Set failed: $msg")
            }
        }, MediaConstraints())
    }

    private fun createPeerConnection() {
        if (peerConnection != null) {
            Log.d(TAG, "createPeerConnection: PeerConnection already exists, returning.")
            return
        }
        Log.d(TAG, "createPeerConnection: Creating new PeerConnection.")
        val config = PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer()
            )
        ).apply {
            // Optional: Add TURN servers if needed for complex NAT traversals
            // For example, if you have a TURN server configured:
            // iceServers.add(PeerConnection.IceServer.builder("turn:your.turn.server.com:3478")
            //     .setUsername("user")
            //     .setPassword("your_turn_password")
            //     .createIceServer())

            // Ensure all network interfaces are considered (good for emulators/devices)
            // networkIgnoreMask = 0 // Not directly in RTCConfiguration, usually in PeerConnectionFactory.Options
            // Check your PeerConnectionFactory.Options if you set this elsewhere.

            // iceTransportsType = PeerConnection.IceTransportsType.RELAY // Only if you want to force TURN
            // iceCandidatePoolSize = 10 // Consider increasing for faster gathering
        }
        peerConnection = peerFactory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate?) {
                c?.let {
                    Log.d(TAG, "onIceCandidate: Generated ICE candidate. SdpMid: ${it.sdpMid}, SdpMLineIndex: ${it.sdpMLineIndex}, Candidate: ${it.sdp.take(50)}...")
                    signalingClient.sendIceCandidate(it)
                } ?: Log.w(TAG, "onIceCandidate: Received null ICE candidate.")
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "onIceConnectionChange: ICE connection state changed to: $state")
                if (state == PeerConnection.IceConnectionState.DISCONNECTED || state == PeerConnection.IceConnectionState.FAILED) {
                    Log.w(TAG, "onIceConnectionChange: Peer disconnected or failed. State: $state")
                    listener.onPeerDisconnected()
                } else if (state == PeerConnection.IceConnectionState.CONNECTED) {
                    Log.d(TAG, "onIceConnectionChange: ICE connection is CONNECTED.")
                    // At this point, media should be able to flow
                }
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.d(TAG, "onSignalingChange: Signaling state changed to: $state")
            }
            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) {
                    Log.d(TAG, "onTrack: Remote video track received. Adding sink to remoteRenderer.")
                    track.addSink(remoteRenderer)
                } else if (track is AudioTrack) {
                    Log.d(TAG, "onTrack: Remote audio track received (not currently rendered).")
                } else {
                    Log.d(TAG, "onTrack: Received unknown track type: $track")
                }
            }

            // --- Unused methods (for debugging, add logs if you suspect issues here) ---
            override fun onAddStream(p0: MediaStream?) {
                Log.d(TAG, "onAddStream: Old API - MediaStream added: ${p0?.id}")
                // This is less common with Unified Plan, onTrack is preferred
            }
            override fun onDataChannel(p0: DataChannel?) { Log.d(TAG, "onDataChannel: Data channel received.") }
            override fun onIceConnectionReceivingChange(p0: Boolean) { Log.d(TAG, "onIceConnectionReceivingChange: $p0") }
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) { Log.d(TAG, "onIceGatheringChange: $p0") }
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) { Log.d(TAG, "onIceCandidatesRemoved: ${p0?.size} candidates removed.") }
            override fun onRemoveStream(p0: MediaStream?) { Log.d(TAG, "onRemoveStream: Old API - MediaStream removed: ${p0?.id}") }
            override fun onRenegotiationNeeded() { Log.d(TAG, "onRenegotiationNeeded: Renegotiation needed.") }
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>) {
                Log.d(TAG, "onAddTrack: Deprecated API - New track added. MediaStream count: ${mediaStreams.size}")
                // This is the deprecated `onAddTrack` but often still implemented.
                // The `onTrack` callback (above) is the preferred method for Unified Plan.
                for (stream in mediaStreams) {
                    val videoTrack = stream.videoTracks.firstOrNull()
                    if (videoTrack != null) {
                        Log.d(TAG, "onAddTrack: Found video track, adding sink to remoteRenderer.")
                        videoTrack.addSink(remoteRenderer)
                    }
                }
            }
            // --- End Unused methods ---
        })
        Log.d(TAG, "createPeerConnection: PeerConnection created with STUN server.")
    }

    private fun setupLocalVideo() {
        if (videoCapturer != null) {
            Log.d(TAG, "setupLocalVideo: Video capturer already exists, returning.")
            return
        }

        Log.d(TAG, "setupLocalVideo: Initializing ScreenCapturerAndroid.")
        videoCapturer = ScreenCapturerAndroid(
            mediaProjectionIntent,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection.Callback: Screen capture stopped.")
                    listener.onPeerDisconnected()
                }
            }
        )

        // For screen sharing, consider passing 'true' for isScreencast.
        // It might hint to WebRTC to optimize for screen content (e.g., text).
        videoSource = peerFactory.createVideoSource(false) // Changed from true in previous version
        val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        videoCapturer!!.initialize(helper, context, videoSource!!.capturerObserver)
        Log.d(TAG, "setupLocalVideo: Video capturer initialized. Starting capture.")
        // Adjust resolution (720, 1280) and FPS (30) as needed for your target device and network.
        // For landscape, swap width and height: (1280, 720).
        videoCapturer!!.startCapture(720, 1280, 30)
        Log.d(TAG, "setupLocalVideo: Capture started at 720x1280@30fps.")


        val track = peerFactory.createVideoTrack("ARDAMSv0", videoSource)
        Log.d(TAG, "setupLocalVideo: Video track created: ${track.id()}.")
        track.addSink(localRenderer)
        Log.d(TAG, "setupLocalVideo: Local video track added to localRenderer.")

        // Add the video track to the PeerConnection for sending
        // Note: You might also add an audio track here if you were capturing audio from the screen.
        peerConnection?.addTrack(track)
        Log.d(TAG, "setupLocalVideo: Video track added to PeerConnection.")
    }

    fun stop() {
        Log.d(TAG, "stop: Stopping WebRtcClient resources.")
        try {
            videoCapturer?.stopCapture()
            Log.d(TAG, "stop: Video capturer stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "stop: Error stopping video capturer: ${e.message}", e)
        }
        videoCapturer?.dispose()
        videoSource?.dispose()
        peerConnection?.dispose()
        Log.d(TAG, "stop: Video capturer, source, and peer connection disposed.")

        videoCapturer = null
        videoSource = null
        peerConnection = null
        Log.d(TAG, "stop: WebRtcClient resources nulled.")
    }
}