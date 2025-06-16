package com.example.splitterrr.utils.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerationAndroid
import org.webrtc.CameraEnumerationAndroid.CaptureFormat
import org.webrtc.CameraEnumerator
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.IceConnectionState
import org.webrtc.PeerConnection.IceGatheringState
import org.webrtc.PeerConnection.IceServer
import org.webrtc.PeerConnection.RTCConfiguration
import org.webrtc.PeerConnection.SignalingState
import org.webrtc.PeerConnectionFactory
import org.webrtc.PeerConnectionFactory.InitializationOptions
import org.webrtc.RtpSender
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.Size
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoDecoderFactory
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoSource
import java.net.URISyntaxException

class PeerConnectionClient(
    private val roomId: String,
    private val mListener: RtcListener,
    host: String?,
    private val rootEglBase: EglBase
) {
    private var factory: PeerConnectionFactory?
    private val pcConstraints = MediaConstraints()
    private var localMS: MediaStream? = null // Will hold the screen share stream
    private var videoSource: VideoSource? = null // Will be the ScreenCapturerAndroid's source
    private var audioSource: AudioSource? = null // For microphone audio
    private var videoCapturer: VideoCapturer? = null // Will be ScreenCapturerAndroid
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private lateinit var socketClient: Socket
    private var peer: Peer? = null
    private var mDataChannel: DataChannel? = null

    // Keep track of the local RtpSenders (important for Unified Plan)
    private var localVideoSender: RtpSender? = null
    private var localAudioSender: RtpSender? = null

    // Store the MediaProjection permission data temporarily if needed
    private var pendingMediaProjectionPermissionResultData: Intent? = null
    private var isScreenShareActive = false // Flag to track screen sharing state


    /**
     * Implement this interface to be notified of events.
     */
    interface RtcListener {
        fun onStatusChanged(newStatus: String?)

        fun onAddLocalStream(localStream: MediaStream?)

        fun onRemoveLocalStream(localStream: MediaStream?)

        fun onAddRemoteStream(remoteStream: MediaStream?) // Still called, but video won't be rendered

        fun onRemoveRemoteStream()

        fun onDataChannelMessage(message: String?)

        fun onDataChannelStateChange(state: DataChannel.State?)

        fun onPeersConnectionStatusChange(success: Boolean)
    }

    private inner class MessageHandler {
        // ... (onConnect, onDisconnect, onOffer, onAnswer, onNewIceCandidate are unchanged) ...
        val onConnect: Emitter.Listener = Emitter.Listener {
            val obj = JSONObject()
            try {
                obj.put("roomId", roomId)
                socketClient.emit("join room", obj)
            } catch (e: JSONException) {
                e.printStackTrace()
            }
        }

        val onDisconnect: Emitter.Listener =
            Emitter.Listener { args -> Log.d(TAG, "Socket disconnected") }

        val onNewUserJoined: Emitter.Listener = Emitter.Listener {
            Log.d(TAG, "onNewUserJoined: Remote user joined. Creating Peer and offer.")
            peer = Peer() // Create peer when a new user joins

            // *** NEW LOGIC: Add tracks to the newly created peer if localMS is ready ***
            if (localMS != null && isScreenShareActive) { // Ensure localMS exists and screen share is meant to be active
                addLocalTracksToPeer()
            } else {
                Log.w(
                    TAG,
                    "onNewUserJoined: localMS not ready or screen share inactive. Peer will create offer without tracks initially."
                )
            }

            peer!!.pc!!.createOffer(peer, pcConstraints)
        }

        val onOffer: Emitter.Listener = Emitter.Listener { args ->
            val data = args[0] as JSONObject
            Log.d(TAG, "onOffer: Received offer.")

            if (peer == null) {
                peer = Peer() // Create peer if not already set (e.g., first offer received)
                // *** NEW LOGIC: Add tracks to the newly created peer if localMS is ready ***
                if (localMS != null && isScreenShareActive) { // Ensure localMS exists and screen share is meant to be active
                    addLocalTracksToPeer()
                } else {
                    Log.w(
                        TAG,
                        "onOffer: localMS not ready or screen share inactive. Peer will set remote description and create answer."
                    )
                }
            }

            try {
                val offer = data.getJSONObject("offer")

                val sdp = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(offer.getString("type")),
                    offer.getString("sdp")
                )
                peer!!.pc!!.setRemoteDescription(peer, sdp)
                peer!!.pc!!.createAnswer(peer, pcConstraints)
            } catch (e: JSONException) {
                Log.e(TAG, "onOffer: JSON exception: " + e.message)
                e.printStackTrace()
            }
        }

        val onAnswer: Emitter.Listener = Emitter.Listener { args ->
            val data = args[0] as JSONObject
            Log.d(TAG, "onAnswer: Received answer.")
            try {
                val answer = data.getJSONObject("answer")

                val sdp = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(answer.getString("type")),
                    answer.getString("sdp")
                )
                if (peer != null) { // Ensure peer exists
                    peer!!.pc!!.setRemoteDescription(peer, sdp)
                } else {
                    Log.e(TAG, "onAnswer: Peer is null when setting remote description.")
                }
            } catch (e: JSONException) {
                Log.e(TAG, "onAnswer: JSON exception: " + e.message)
                e.printStackTrace()
            }
        }

        val onNewIceCandidate: Emitter.Listener = Emitter.Listener { args ->
            val data = args[0] as JSONObject
            try {
                val iceCandidate = data.getJSONObject("iceCandidate")

                if (peer != null && peer!!.pc != null && peer!!.pc!!.remoteDescription != null) {
                    val candidate = IceCandidate(
                        iceCandidate.getString("sdpMid"),
                        iceCandidate.getInt("sdpMLineIndex"),
                        iceCandidate.getString("candidate")
                    )
                    peer!!.pc!!.addIceCandidate(candidate)
                } else {
                    Log.w(
                        TAG,
                        "onNewIceCandidate: Peer connection or remote description not ready. Candidate ignored."
                    )
                }
            } catch (e: JSONException) {
                Log.e(TAG, "onNewIceCandidate: JSON exception: " + e.message)
                e.printStackTrace()
            }
        }
    }

    private inner class Peer : SdpObserver, PeerConnection.Observer, DataChannel.Observer {
        val pc: PeerConnection?

        override fun onCreateSuccess(sdp: SessionDescription) {
            Log.d(TAG, "onCreateSuccess: Created SDP type: " + sdp.type.canonicalForm())
            pc!!.setLocalDescription(
                this@Peer,
                sdp
            ) // Set local description, this will trigger onSetSuccess

            try {
                val payload = JSONObject()
                val desc = JSONObject()
                desc.put("type", sdp.type.canonicalForm())
                desc.put("sdp", sdp.description)

                payload.put(sdp.type.canonicalForm(), desc)
                payload.put("roomId", roomId)

                socketClient.emit(sdp.type.canonicalForm(), payload)
            } catch (e: JSONException) {
                Log.e(TAG, "onCreateSuccess: JSON exception: " + e.message)
                e.printStackTrace()
            }
        }

        override fun onSetSuccess() {
            Log.d(TAG, "onSetSuccess: SDP set successfully.")
        }

        override fun onCreateFailure(s: String) {
            Log.e(TAG, "onCreateFailure: $s")
        }

        override fun onSetFailure(s: String) {
            Log.e(TAG, "onSetFailure: $s")
        }

        override fun onSignalingChange(signalingState: SignalingState) {
            Log.d(
                TAG,
                "onSignalingChange: $signalingState"
            )
        }

        override fun onIceConnectionChange(iceConnectionState: IceConnectionState) {
            Log.d(
                TAG,
                "onIceConnectionChange: $iceConnectionState"
            )
            if (iceConnectionState == IceConnectionState.DISCONNECTED) {
                mListener.onStatusChanged("DISCONNECTED")
                mListener.onRemoveRemoteStream()

                if (mDataChannel != null) {
                    mDataChannel!!.unregisterObserver()
                    mDataChannel!!.dispose()
                    mDataChannel = null
                }

                pc!!.dispose()
                // Null out the peer reference
                peer = null // IMPORTANT: Clear the reference when connection ends

                mListener.onPeersConnectionStatusChange(false)
            } else if (iceConnectionState == IceConnectionState.CONNECTED) {
                Log.d(TAG, "Peers connected")
                mListener.onStatusChanged("CONNECTED")
                mListener.onPeersConnectionStatusChange(true)
            }
        }

        override fun onIceConnectionReceivingChange(b: Boolean) {
            Log.d(
                TAG,
                "onIceConnectionReceivingChange: $b"
            )
        }

        override fun onIceGatheringChange(iceGatheringState: IceGatheringState) {
            Log.d(
                TAG,
                "onIceGatheringChange: $iceGatheringState"
            )
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            Log.d(
                TAG,
                "onIceCandidate: " + candidate.sdpMid + " " + candidate.sdpMLineIndex + " " + candidate.sdp
            )
            try {
                val payload = JSONObject()
                val iceCandidate = JSONObject()

                iceCandidate.put("sdpMLineIndex", candidate.sdpMLineIndex)
                iceCandidate.put("sdpMid", candidate.sdpMid)
                iceCandidate.put("candidate", candidate.sdp)

                payload.put("iceCandidate", iceCandidate)
                payload.put("roomId", roomId)

                socketClient.emit("new ice candidate", payload)
            } catch (e: JSONException) {
                Log.e(TAG, "onIceCandidate: JSON exception: " + e.message)
                e.printStackTrace()
            }
        }

        override fun onIceCandidatesRemoved(iceCandidates: Array<IceCandidate>) {
            Log.d(TAG, "onIceCandidatesRemoved.")
            if (peer != null && peer!!.pc != null) {
                peer!!.pc!!.removeIceCandidates(iceCandidates)
            }
        }

        override fun onAddStream(mediaStream: MediaStream) {
            Log.d(TAG, "onAddStream " + mediaStream.id)
            // This is for incoming streams to this Android device.
            // As per requirements, we don't want to display remote video.
            if (!mediaStream.videoTracks.isEmpty()) {
                val remoteVideoTrack = mediaStream.videoTracks[0]
                Log.d(TAG, "Remote video track received. Disabling it as per send-only mode.")
                remoteVideoTrack.setEnabled(false) // Disable to stop receiving video frames
            }
            if (!mediaStream.audioTracks.isEmpty()) {
                mediaStream.audioTracks[0].setEnabled(true) // Keep audio enabled if you want to hear it
            }
            mListener.onAddRemoteStream(mediaStream) // Still notify for audio/status
        }

        override fun onRemoveStream(mediaStream: MediaStream) {
            Log.d(TAG, "onRemoveStream " + mediaStream.id)
            mListener.onRemoveRemoteStream()
        }

        override fun onDataChannel(dataChannel: DataChannel) {
            Log.d(TAG, "onDataChannel " + dataChannel.state())
            mDataChannel = dataChannel
            mDataChannel!!.registerObserver(this)
        }

        override fun onRenegotiationNeeded() {
            Log.d(TAG, "onRenegotiationNeeded: Creating offer for renegotiation.")
            // If this peer has tracks to send (which it should if screen sharing is active),
            // it should create an offer.
            if (peer === this && (localVideoSender != null || localAudioSender != null)) {
                pc!!.createOffer(this@Peer, pcConstraints)
            } else {
                Log.d(TAG, "onRenegotiationNeeded: No local tracks to send or not the offerer.")
            }
        }

        init {
            Log.d(TAG, "New Peer created.")
            val rtcConfig = RTCConfiguration(ArrayList())
            rtcConfig.iceServers.add(
                IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )

            this.pc = factory!!.createPeerConnection(rtcConfig, this)
            mListener.onStatusChanged("CONNECTING")
        }

        override fun onBufferedAmountChange(l: Long) {
            // Not used for this example
        }

        override fun onStateChange() {
            Log.d(TAG, "DataChannel onStateChange: " + mDataChannel!!.state())
            mListener.onDataChannelStateChange(mDataChannel!!.state())
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            if (buffer.binary) {
                Log.d(TAG, "Received binary data channel message.")
            } else {
                val data = buffer.data
                val bytes = ByteArray(data.remaining())
                data[bytes]
                val message = String(bytes)
                mListener.onDataChannelMessage(message)
                Log.d(
                    TAG,
                    "Received text data channel message: $message"
                )
            }
        }
    }

    init {
        val initializationOptions =
            InitializationOptions.builder(mListener as Context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        val options = PeerConnectionFactory.Options()
        val encoderFactory: VideoEncoderFactory =
            DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, true)
        val decoderFactory: VideoDecoderFactory =
            DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoDecoderFactory(decoderFactory)
            .setVideoEncoderFactory(encoderFactory)
            .createPeerConnectionFactory()

        val messageHandler: MessageHandler = MessageHandler()

        try {
            socketClient = IO.socket(host)
        } catch (e: URISyntaxException) {
            e.printStackTrace()
        }
        socketClient.on(Socket.EVENT_CONNECT, messageHandler.onConnect)
        socketClient.on("new user joined", messageHandler.onNewUserJoined)
        socketClient.on("offer", messageHandler.onOffer)
        socketClient.on("answer", messageHandler.onAnswer)
        socketClient.on("new ice candidate", messageHandler.onNewIceCandidate)
        socketClient.on(Socket.EVENT_DISCONNECT, messageHandler.onDisconnect)

        socketClient.connect()

        pcConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        pcConstraints.mandatory.add(
            MediaConstraints.KeyValuePair(
                "OfferToReceiveVideo",
                "true"
            )
        ) // We still offer to receive, but discard it.
        pcConstraints.optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))

        // These constraints are more for initial SDP, actual capture resolution is set in startCapture
        pcConstraints.mandatory.add(MediaConstraints.KeyValuePair("maxHeight", 1080.toString()))
        pcConstraints.mandatory.add(MediaConstraints.KeyValuePair("maxWidth", 2400.toString()))
        pcConstraints.mandatory.add(MediaConstraints.KeyValuePair("maxFrameRate", 30.toString()))
        pcConstraints.mandatory.add(MediaConstraints.KeyValuePair("minFrameRate", 30.toString()))
    }

    fun onPause() { /* No change */
    }

    fun onResume() { /* No change */
    }

    fun onDestroy() {
        Log.d(TAG, "PeerConnectionClient onDestroy initiated.")
        socketClient.close()

        // Dispose PeerConnection components first if they exist
        if (peer != null && peer!!.pc != null) {
            Log.d(TAG, "Disposing PeerConnection tracks and peer.")
            // Remove RtpSenders (tracks) explicitly before disposing PC
            if (localVideoSender != null) {
                peer!!.pc!!.removeTrack(localVideoSender)
                localVideoSender = null
            }
            if (localAudioSender != null) {
                peer!!.pc!!.removeTrack(localAudioSender)
                localAudioSender = null
            }
            peer!!.pc!!.dispose()
            peer = null
        }

        // Dispose media stream if it exists
        if (localMS != null) {
            Log.d(TAG, "Disposing local MediaStream.")
            localMS!!.dispose()
            localMS = null
        }

        // Dispose capturer, sources, and helpers
        Log.d(TAG, "Stopping and disposing capturer, sources, and helper.")
        if (videoCapturer != null) {
            try {
                videoCapturer!!.stopCapture()
            } catch (e: InterruptedException) {
                Log.e(TAG, "Error stopping video capturer: " + e.message)
            } finally {
                videoCapturer!!.dispose()
                videoCapturer = null
            }
        }
        if (videoSource != null) {
            videoSource!!.dispose()
            videoSource = null
        }
        if (audioSource != null) {
            audioSource!!.dispose()
            audioSource = null
        }
        if (surfaceTextureHelper != null) {
            surfaceTextureHelper!!.dispose()
            surfaceTextureHelper = null
        }

        // Dispose data channel
        if (mDataChannel != null) {
            mDataChannel!!.unregisterObserver()
            mDataChannel!!.dispose()
            mDataChannel = null
        }

        // Dispose PeerConnectionFactory last
        Log.d(TAG, "Disposing PeerConnectionFactory.")
        if (factory != null) {
            factory!!.dispose()
            factory = null
        }

        PeerConnectionFactory.stopInternalTracingCapture()
        PeerConnectionFactory.shutdownInternalTracer()

        Log.d(TAG, "PeerConnectionClient cleanup complete.")
    }

    fun start() {
        Log.d(
            TAG,
            "PeerConnectionClient started. Waiting for screen capture intent from CallActivity."
        )
        // We no longer call setCamera() here. Screen capture setup is managed by createDeviceCapture.
    }

    // New helper method to add local tracks to the PeerConnection
    private fun addLocalTracksToPeer() {
        if (peer != null && peer!!.pc != null && localMS != null) {
            Log.d(TAG, "Attempting to add local tracks to PeerConnection.")

            // Ensure old senders are removed before adding new ones, though createDeviceCapture handles this.
            // This is a safeguard if this method is called independently.
            if (localVideoSender != null) {
                peer!!.pc!!.removeTrack(localVideoSender)
                localVideoSender = null
            }
            if (localAudioSender != null) {
                peer!!.pc!!.removeTrack(localAudioSender)
                localAudioSender = null
            }

            // Add video track
            if (!localMS!!.videoTracks.isEmpty()) {
                val videoTrack = localMS!!.videoTracks[0]
                localVideoSender = peer!!.pc!!.addTrack(
                    videoTrack, listOf(
                        localMS!!.id
                    )
                )
                Log.d(
                    TAG,
                    "Added local video track to peer connection. Sender: " + (if (localVideoSender != null) localVideoSender!!.id() else "null")
                )
            } else {
                Log.w(TAG, "No video track found in localMS to add.")
            }

            // Add audio track
            if (!localMS!!.audioTracks.isEmpty()) {
                val audioTrack = localMS!!.audioTracks[0]
                localAudioSender = peer!!.pc!!.addTrack(
                    audioTrack, listOf(
                        localMS!!.id
                    )
                )
                Log.d(
                    TAG,
                    "Added local audio track to peer connection. Sender: " + (if (localAudioSender != null) localAudioSender!!.id() else "null")
                )
            } else {
                Log.w(TAG, "No audio track found in localMS to add.")
            }

            // If tracks were just added, renegotiation is needed.
            peer!!.pc!!.createOffer(peer, pcConstraints)
        } else {
            Log.e(
                TAG,
                "Cannot add local tracks to peer: Peer, PeerConnection, or local stream is null. Peer: " + (peer == null) + ", Peer.pc: " + (peer != null && peer!!.pc == null) + ", localMS: " + (localMS == null)
            )
        }
    }


    fun createDeviceCapture(isScreencast: Boolean, mediaProjectionPermissionResultData: Intent?) {
        Log.d(
            TAG,
            "createDeviceCapture called. isScreencast: $isScreencast"
        )

        // Store the permission data if we're starting screen share, for later use if peer connects late
        if (isScreencast) {
            this.pendingMediaProjectionPermissionResultData = mediaProjectionPermissionResultData
        }

        // 1. Stop and dispose of any existing capturer/source/stream components
        if (videoCapturer != null) {
            try {
                videoCapturer!!.stopCapture()
                videoCapturer!!.dispose()
            } catch (e: InterruptedException) {
                Log.e(TAG, "Error stopping video capturer: " + e.message)
            } finally {
                videoCapturer = null
            }
        }
        if (videoSource != null) {
            videoSource!!.dispose()
            videoSource = null
        }
        if (audioSource != null) {
            audioSource!!.dispose()
            audioSource = null
        }
        if (surfaceTextureHelper != null) {
            surfaceTextureHelper!!.dispose()
            surfaceTextureHelper = null
        }

        // 2. Remove existing tracks from PeerConnection and local stream
        // This is crucial for proper renegotiation when switching streams (e.g., stopping share)
        if (peer != null && peer!!.pc != null) {
            Log.d(TAG, "Removing existing tracks from PeerConnection.")
            if (localVideoSender != null) {
                peer!!.pc!!.removeTrack(localVideoSender)
                localVideoSender = null
            }
            if (localAudioSender != null) {
                peer!!.pc!!.removeTrack(localAudioSender)
                localAudioSender = null
            }
            // Dispose of the local MediaStream if it was previously created
            if (localMS != null) {
                localMS!!.dispose()
                localMS = null
            }
            // Trigger renegotiation immediately after removing tracks (if peer exists)
            peer!!.pc!!.createOffer(peer, pcConstraints)
        }
        mListener.onRemoveLocalStream(null) // Notify UI to remove local stream preview

        if (isScreencast) {
            if (this.pendingMediaProjectionPermissionResultData == null) {
                Log.e(TAG, "MediaProjection permission data is null for screen capture. Aborting.")
                mListener.onStatusChanged("Failed to start screen capture: Permission data missing.")
                isScreenShareActive = false
                return
            }
            Log.d(TAG, "Initializing ScreenCapturerAndroid.")
            videoCapturer = ScreenCapturerAndroid(
                this.pendingMediaProjectionPermissionResultData,
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.d(TAG, "Screen sharing stopped by system or user.")
                        mListener.onStatusChanged("Screen sharing stopped.")
                        // You might want to automatically call createDeviceCapture(false, null) here
                        // to formally stop sending the stream.
                        // createDeviceCapture(false, null);
                    }
                })

            // Initialize and start the new video capturer
            if (videoCapturer == null) {
                Log.e(TAG, "Video capturer is null after creation attempt. Aborting.")
                mListener.onStatusChanged("Failed to create video capturer.")
                isScreenShareActive = false
                return
            }

            videoSource = factory!!.createVideoSource((videoCapturer as ScreenCapturerAndroid).isScreencast())
            surfaceTextureHelper =
                SurfaceTextureHelper.create("CaptureThread", rootEglBase.eglBaseContext)
            videoCapturer?.initialize(
                surfaceTextureHelper,
                mListener as Context,
                videoSource?.getCapturerObserver()
            )

            val dimensions: Utils.ScreenDimensions = Utils.getScreenDimentions(mListener as Context)
            val fps: Int = Utils.getFps(mListener as Context)

            Log.d(
                TAG,
                (("Starting capture with: " + dimensions.screenWidth).toString() + "x" + dimensions.screenHeight).toString() + "@" + fps + "fps"
            )
            videoCapturer?.startCapture(dimensions.screenWidth, dimensions.screenHeight, fps)

            // Create new local MediaStream for the screen share video and audio
            localMS = factory!!.createLocalMediaStream("SCREEN_SHARE_STREAM")
            val screenVideoTrack =
                factory!!.createVideoTrack("SCREEN_SHARE_VIDEO_TRACK", videoSource)
            screenVideoTrack.setEnabled(true)
            localMS?.addTrack(screenVideoTrack)

            audioSource = factory!!.createAudioSource(MediaConstraints()) // Microphone audio source
            val micAudioTrack = factory!!.createAudioTrack("MICROPHONE_AUDIO_TRACK", audioSource)
            micAudioTrack.setEnabled(true)
            localMS?.addTrack(micAudioTrack)

            isScreenShareActive = true
            Log.d(TAG, "Local MediaStream for screen share created and active.")

            // *** IMPORTANT: Add tracks to PeerConnection IF it's already established ***
            if (peer != null && peer!!.pc != null) {
                Log.d(
                    TAG,
                    "Peer connection already exists. Adding local tracks immediately and triggering renegotiation."
                )
                addLocalTracksToPeer()
            } else {
                Log.d(
                    TAG,
                    "Peer connection not yet established. Tracks will be added when peer connects and triggers Peer creation."
                )
            }

            mListener.onAddLocalStream(localMS) // Notify listener for local preview
        } else {
            // Logic for stopping screen share (explicitly called with isScreencast = false)
            Log.d(TAG, "Stopping screen capture.")
            isScreenShareActive = false
            // Existing tracks were already removed at the beginning of this method.
            mListener.onStatusChanged("Screen sharing stopped.")
        }
    }

    // ... (toggleAudio, toggleVideo, createDataChannel, sendDataChannelMessage, helper methods remain largely same) ...
    // Note: toggleVideo now enables/disables the screen share video track.
    fun toggleAudio(enable: Boolean) {
        if (localMS != null && !localMS!!.audioTracks.isEmpty()) {
            val audioTrack = localMS!!.audioTracks[0]
            audioTrack.setEnabled(enable)
            Log.d(
                TAG,
                "Local audio track enabled: $enable"
            )
        } else {
            Log.w(TAG, "toggleAudio: Local audio track not found or stream not initialized.")
        }
    }

    fun toggleVideo(enable: Boolean) {
        if (localMS != null && !localMS!!.videoTracks.isEmpty()) {
            val videoTrack = localMS!!.videoTracks[0]
            videoTrack.setEnabled(enable)
            Log.d(
                TAG,
                "Local screen share video track enabled: $enable"
            )
            mListener.onStatusChanged("Screen share video " + (if (enable) "enabled" else "disabled"))
        } else {
            Log.w(
                TAG,
                "toggleVideo: Local screen share video track not found or stream not initialized."
            )
        }
    }

    // These methods (setCamera, getVideoCapturer, createCapturer, switchCamera) are not used for screen share only mode.
    // They are kept as stubs to prevent compile errors from CallActivity if it still attempts to call them.
    private fun setCamera() {
        Log.w(TAG, "setCamera() called, but camera capture is not intended.")
    }

    private fun getVideoCapturer(): VideoCapturer? {
        Log.w(TAG, "getVideoCapturer() called, returning null.")
        return null
    }

    private fun createCapturer(enumerator: CameraEnumerator, frontFacing: Boolean): VideoCapturer? {
        Log.w(TAG, "createCapturer() called, returning null.")
        return null
    }

    fun switchCamera() {
        Log.d(TAG, "switchCamera() called. Camera switching is disabled.")
        mListener.onStatusChanged("Camera switching is disabled. Currently screen sharing.")
    }

    fun getSupportedFormats(cameraId: String?): List<CaptureFormat>? {
        val enumerator = Camera2Enumerator(mListener as Context)
        return enumerator.getSupportedFormats(cameraId)
    }

    fun findClosestCaptureFormat(cameraId: String?, width: Int, height: Int): Size {
        val sizes: MutableList<Size> = ArrayList()
        val formats = getSupportedFormats(cameraId)
        if (formats != null) {
            for (format in formats) {
                sizes.add(Size(format.width, format.height))
            }
        }
        return CameraEnumerationAndroid.getClosestSupportedSize(sizes, width, height)
    }

    companion object {
        private val TAG: String = PeerConnectionClient::class.java.canonicalName
    }
}