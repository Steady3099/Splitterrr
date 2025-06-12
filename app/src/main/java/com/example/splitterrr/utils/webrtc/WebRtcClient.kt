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
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        peerFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()
    }

    fun start() {
        signalingClient.onOfferReceived = { sdp ->
            createPeerConnection()
            peerConnection?.setRemoteDescription(SimpleSdpObserver(), sdp)
            peerConnection?.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(answer: SessionDescription?) {
                    answer?.let {
                        peerConnection?.setLocalDescription(SimpleSdpObserver(), it)
                        signalingClient.sendAnswer(it)
                        listener.onPeerConnected()
                    }
                }
                override fun onSetSuccess() {}
                override fun onSetFailure(msg: String?) = listener.onError("Set failed: $msg")
                override fun onCreateFailure(msg: String?) = listener.onError("Answer failed: $msg")
            }, MediaConstraints())
        }

        signalingClient.onIceCandidateReceived = { candidate ->
            peerConnection?.addIceCandidate(candidate)
        }

        signalingClient.onError = { err ->
            listener.onError("Signaling error: $err")
        }
    }

    fun initiateCall() {
        createPeerConnection()
        setupLocalVideo()
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(offer: SessionDescription?) {
                offer?.let {
                    peerConnection?.setLocalDescription(SimpleSdpObserver(), it)
                    signalingClient.sendOffer(it)
                    listener.onPeerConnected()
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(msg: String?) = listener.onError("Offer failed: $msg")
            override fun onSetFailure(msg: String?) = listener.onError("Set failed: $msg")
        }, MediaConstraints())
    }

    private fun createPeerConnection() {
        if (peerConnection != null) return
        val config = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        )
        peerConnection = peerFactory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate?) {
                c?.let { signalingClient.sendIceCandidate(it) }
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                if (state == PeerConnection.IceConnectionState.DISCONNECTED) listener.onPeerDisconnected()
            }
            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) {
                    Log.d(TAG, "onTrack: remote video")
                    track.addSink(remoteRenderer)
                }
            }

            // Unused methods
            override fun onAddStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, mediaStreams: Array<out MediaStream>) {
                for (stream in mediaStreams) {
                    val videoTrack = stream.videoTracks.firstOrNull()
                    videoTrack?.addSink(remoteRenderer)
                }
            }
        })
    }

    private fun setupLocalVideo() {
        if (videoCapturer != null) return

        videoCapturer = ScreenCapturerAndroid(
            mediaProjectionIntent,
            object : MediaProjection.Callback() {
                override fun onStop() = listener.onPeerDisconnected()
            }
        )

        videoSource = peerFactory.createVideoSource(false)
        val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        videoCapturer!!.initialize(helper, context, videoSource!!.capturerObserver)
        videoCapturer!!.startCapture(720, 1280, 30)

        val track = peerFactory.createVideoTrack("ARDAMSv0", videoSource)
        track.addSink(localRenderer)
        peerConnection?.addTrack(track)
    }

    fun stop() {
        try { videoCapturer?.stopCapture() } catch (_: Exception) {}
        videoCapturer?.dispose()
        videoSource?.dispose()
        peerConnection?.dispose()
        videoCapturer = null
        videoSource = null
        peerConnection = null
    }
}


