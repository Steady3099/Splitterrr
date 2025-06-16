package com.example.splitterrr.utils.webrtc;

import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerationAndroid;
import org.webrtc.CameraEnumerator;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpSender;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.Size;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoEncoderFactory;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

public class PeerConnectionClient {
    private final static String TAG = PeerConnectionClient.class.getCanonicalName();
    private PeerConnectionFactory factory;
    private final MediaConstraints pcConstraints = new MediaConstraints();
    private MediaStream localMS; // Will hold the screen share stream
    private VideoSource videoSource; // Will be the ScreenCapturerAndroid's source
    private AudioSource audioSource; // For microphone audio
    private VideoCapturer videoCapturer; // Will be ScreenCapturerAndroid
    private SurfaceTextureHelper surfaceTextureHelper;
    private final RtcListener mListener;
    private Socket socketClient;
    private Peer peer;
    private final String roomId;
    private final EglBase rootEglBase;
    private DataChannel mDataChannel;

    // Keep track of the local RtpSenders (important for Unified Plan)
    private RtpSender localVideoSender;
    private RtpSender localAudioSender;

    // Store the MediaProjection permission data temporarily if needed
    private Intent pendingMediaProjectionPermissionResultData;
    private boolean isScreenShareActive = false; // Flag to track screen sharing state


    /**
     * Implement this interface to be notified of events.
     */
    public interface RtcListener {
        void onStatusChanged(String newStatus);

        void onAddLocalStream(MediaStream localStream);

        void onRemoveLocalStream(MediaStream localStream);

        void onAddRemoteStream(MediaStream remoteStream); // Still called, but video won't be rendered

        void onRemoveRemoteStream();

        void onDataChannelMessage(String message);

        void onDataChannelStateChange(DataChannel.State state);

        void onPeersConnectionStatusChange(boolean success);
    }

    private class MessageHandler {
        // ... (onConnect, onDisconnect, onOffer, onAnswer, onNewIceCandidate are unchanged) ...
        private final Emitter.Listener onConnect = new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                JSONObject obj = new JSONObject();
                try {
                    obj.put("roomId", roomId);
                    socketClient.emit("join room", obj);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        };

        private final Emitter.Listener onDisconnect = args -> Log.d(TAG, "Socket disconnected");

        private final Emitter.Listener onNewUserJoined = new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                Log.d(TAG, "onNewUserJoined: Remote user joined. Creating Peer and offer.");
                peer = new Peer(); // Create peer when a new user joins

                // *** NEW LOGIC: Add tracks to the newly created peer if localMS is ready ***
                if (localMS != null && isScreenShareActive) { // Ensure localMS exists and screen share is meant to be active
                    addLocalTracksToPeer();
                } else {
                    Log.w(TAG, "onNewUserJoined: localMS not ready or screen share inactive. Peer will create offer without tracks initially.");
                }

                peer.pc.createOffer(peer, pcConstraints);
            }
        };

        private final Emitter.Listener onOffer = new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                JSONObject data = (JSONObject) args[0];
                Log.d(TAG, "onOffer: Received offer.");

                if (peer == null) {
                    peer = new Peer(); // Create peer if not already set (e.g., first offer received)
                    // *** NEW LOGIC: Add tracks to the newly created peer if localMS is ready ***
                    if (localMS != null && isScreenShareActive) { // Ensure localMS exists and screen share is meant to be active
                        addLocalTracksToPeer();
                    } else {
                        Log.w(TAG, "onOffer: localMS not ready or screen share inactive. Peer will set remote description and create answer.");
                    }
                }

                try {
                    JSONObject offer = data.getJSONObject("offer");

                    SessionDescription sdp = new SessionDescription(
                            SessionDescription.Type.fromCanonicalForm(offer.getString("type")),
                            offer.getString("sdp")
                    );
                    peer.pc.setRemoteDescription(peer, sdp);
                    peer.pc.createAnswer(peer, pcConstraints);
                } catch (JSONException e) {
                    Log.e(TAG, "onOffer: JSON exception: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        };

        private final Emitter.Listener onAnswer = new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                JSONObject data = (JSONObject) args[0];
                Log.d(TAG, "onAnswer: Received answer.");
                try {
                    JSONObject answer = data.getJSONObject("answer");

                    SessionDescription sdp = new SessionDescription(
                            SessionDescription.Type.fromCanonicalForm(answer.getString("type")),
                            answer.getString("sdp")
                    );
                    if (peer != null) { // Ensure peer exists
                        peer.pc.setRemoteDescription(peer, sdp);
                    } else {
                        Log.e(TAG, "onAnswer: Peer is null when setting remote description.");
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "onAnswer: JSON exception: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        };

        private final Emitter.Listener onNewIceCandidate = new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                JSONObject data = (JSONObject) args[0];
                try {
                    JSONObject iceCandidate = data.getJSONObject("iceCandidate");

                    if (peer != null && peer.pc != null && peer.pc.getRemoteDescription() != null) {
                        IceCandidate candidate = new IceCandidate(
                                iceCandidate.getString("sdpMid"),
                                iceCandidate.getInt("sdpMLineIndex"),
                                iceCandidate.getString("candidate")
                        );
                        peer.pc.addIceCandidate(candidate);
                    } else {
                        Log.w(TAG, "onNewIceCandidate: Peer connection or remote description not ready. Candidate ignored.");
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "onNewIceCandidate: JSON exception: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        };
    }

    private class Peer implements SdpObserver, PeerConnection.Observer, DataChannel.Observer {
        private final PeerConnection pc;

        @Override
        public void onCreateSuccess(final SessionDescription sdp) {
            Log.d(TAG, "onCreateSuccess: Created SDP type: " + sdp.type.canonicalForm());
            pc.setLocalDescription(Peer.this, sdp); // Set local description, this will trigger onSetSuccess

            try {
                JSONObject payload = new JSONObject();
                JSONObject desc = new JSONObject();
                desc.put("type", sdp.type.canonicalForm());
                desc.put("sdp", sdp.description);

                payload.put(sdp.type.canonicalForm(), desc);
                payload.put("roomId", roomId);

                socketClient.emit(sdp.type.canonicalForm(), payload);
            } catch (JSONException e) {
                Log.e(TAG, "onCreateSuccess: JSON exception: " + e.getMessage());
                e.printStackTrace();
            }
        }

        @Override
        public void onSetSuccess() {
            Log.d(TAG, "onSetSuccess: SDP set successfully.");
        }

        @Override
        public void onCreateFailure(String s) {
            Log.e(TAG, "onCreateFailure: " + s);
        }

        @Override
        public void onSetFailure(String s) {
            Log.e(TAG, "onSetFailure: " + s);
        }

        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {
            Log.d(TAG, "onSignalingChange: " + signalingState);
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
            Log.d(TAG, "onIceConnectionChange: " + iceConnectionState);
            if (iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED) {
                mListener.onStatusChanged("DISCONNECTED");
                mListener.onRemoveRemoteStream();

                if (mDataChannel != null) {
                    mDataChannel.unregisterObserver();
                    mDataChannel.dispose();
                    mDataChannel = null;
                }

                pc.dispose();
                // Null out the peer reference
                peer = null; // IMPORTANT: Clear the reference when connection ends

                mListener.onPeersConnectionStatusChange(false);
            } else if (iceConnectionState == PeerConnection.IceConnectionState.CONNECTED) {
                Log.d(TAG, "Peers connected");
                mListener.onStatusChanged("CONNECTED");
                mListener.onPeersConnectionStatusChange(true);
            }
        }

        @Override
        public void onIceConnectionReceivingChange(boolean b) {
            Log.d(TAG, "onIceConnectionReceivingChange: " + b);
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
            Log.d(TAG, "onIceGatheringChange: " + iceGatheringState);
        }

        @Override
        public void onIceCandidate(final IceCandidate candidate) {
            Log.d(TAG, "onIceCandidate: " + candidate.sdpMid + " " + candidate.sdpMLineIndex + " " + candidate.sdp);
            try {
                JSONObject payload = new JSONObject();
                JSONObject iceCandidate = new JSONObject();

                iceCandidate.put("sdpMLineIndex", candidate.sdpMLineIndex);
                iceCandidate.put("sdpMid", candidate.sdpMid);
                iceCandidate.put("candidate", candidate.sdp);

                payload.put("iceCandidate", iceCandidate);
                payload.put("roomId", roomId);

                socketClient.emit("new ice candidate", payload);
            } catch (JSONException e) {
                Log.e(TAG, "onIceCandidate: JSON exception: " + e.getMessage());
                e.printStackTrace();
            }
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
            Log.d(TAG, "onIceCandidatesRemoved.");
            if (peer != null && peer.pc != null) {
                peer.pc.removeIceCandidates(iceCandidates);
            }
        }

        @Override
        public void onAddStream(MediaStream mediaStream) {
            Log.d(TAG, "onAddStream " + mediaStream.getId());
            // This is for incoming streams to this Android device.
            // As per requirements, we don't want to display remote video.
            if (!mediaStream.videoTracks.isEmpty()) {
                VideoTrack remoteVideoTrack = mediaStream.videoTracks.get(0);
                Log.d(TAG, "Remote video track received. Disabling it as per send-only mode.");
                remoteVideoTrack.setEnabled(false); // Disable to stop receiving video frames
            }
            if (!mediaStream.audioTracks.isEmpty()) {
                mediaStream.audioTracks.get(0).setEnabled(true); // Keep audio enabled if you want to hear it
            }
            mListener.onAddRemoteStream(mediaStream); // Still notify for audio/status
        }

        @Override
        public void onRemoveStream(MediaStream mediaStream) {
            Log.d(TAG, "onRemoveStream " + mediaStream.getId());
            mListener.onRemoveRemoteStream();
        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {
            Log.d(TAG, "onDataChannel " + dataChannel.state());
            mDataChannel = dataChannel;
            mDataChannel.registerObserver(this);
        }

        @Override
        public void onRenegotiationNeeded() {
            Log.d(TAG, "onRenegotiationNeeded: Creating offer for renegotiation.");
            // If this peer has tracks to send (which it should if screen sharing is active),
            // it should create an offer.
            if (peer == this && (localVideoSender != null || localAudioSender != null)) {
                pc.createOffer(Peer.this, pcConstraints);
            } else {
                Log.d(TAG, "onRenegotiationNeeded: No local tracks to send or not the offerer.");
            }
        }

        Peer() {
            Log.d(TAG, "New Peer created.");
            PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(new ArrayList<>());
            rtcConfig.iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());

            this.pc = factory.createPeerConnection(rtcConfig, this);
            mListener.onStatusChanged("CONNECTING");
        }

        @Override
        public void onBufferedAmountChange(long l) {
            // Not used for this example
        }

        @Override
        public void onStateChange() {
            Log.d(TAG, "DataChannel onStateChange: " + mDataChannel.state());
            mListener.onDataChannelStateChange(mDataChannel.state());
        }

        @Override
        public void onMessage(DataChannel.Buffer buffer) {
            if (buffer.binary) {
                Log.d(TAG, "Received binary data channel message.");
            } else {
                ByteBuffer data = buffer.data;
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                String message = new String(bytes);
                mListener.onDataChannelMessage(message);
                Log.d(TAG, "Received text data channel message: " + message);
            }
        }
    }

    public PeerConnectionClient(String roomId, RtcListener listener, String host, EglBase rootEglBase) {
        this.roomId = roomId;
        mListener = listener;
        this.rootEglBase = rootEglBase;

        PeerConnectionFactory.InitializationOptions initializationOptions =
                PeerConnectionFactory.InitializationOptions.builder((Context) listener)
                        .setEnableInternalTracer(true)
                        .createInitializationOptions();
        PeerConnectionFactory.initialize(initializationOptions);

        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        VideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(rootEglBase.getEglBaseContext(), true, true);
        VideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(rootEglBase.getEglBaseContext());

        factory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoDecoderFactory(decoderFactory)
                .setVideoEncoderFactory(encoderFactory)
                .createPeerConnectionFactory();

        MessageHandler messageHandler = new MessageHandler();

        try {
            socketClient = IO.socket(host);
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        socketClient.on(Socket.EVENT_CONNECT, messageHandler.onConnect);
        socketClient.on("new user joined", messageHandler.onNewUserJoined);
        socketClient.on("offer", messageHandler.onOffer);
        socketClient.on("answer", messageHandler.onAnswer);
        socketClient.on("new ice candidate", messageHandler.onNewIceCandidate);
        socketClient.on(Socket.EVENT_DISCONNECT, messageHandler.onDisconnect);

        socketClient.connect();

        pcConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        pcConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true")); // We still offer to receive, but discard it.
        pcConstraints.optional.add(new MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"));

        // These constraints are more for initial SDP, actual capture resolution is set in startCapture
        pcConstraints.mandatory.add(new MediaConstraints.KeyValuePair("maxHeight", Integer.toString(1080)));
        pcConstraints.mandatory.add(new MediaConstraints.KeyValuePair("maxWidth", Integer.toString(2400)));
        pcConstraints.mandatory.add(new MediaConstraints.KeyValuePair("maxFrameRate", Integer.toString(30)));
        pcConstraints.mandatory.add(new MediaConstraints.KeyValuePair("minFrameRate", Integer.toString(30)));
    }

    public void onPause() { /* No change */ }
    public void onResume() { /* No change */ }

    public void onDestroy() {
        Log.d(TAG, "PeerConnectionClient onDestroy initiated.");
        socketClient.close();

        // Dispose PeerConnection components first if they exist
        if (peer != null && peer.pc != null) {
            Log.d(TAG, "Disposing PeerConnection tracks and peer.");
            // Remove RtpSenders (tracks) explicitly before disposing PC
            if (localVideoSender != null) {
                peer.pc.removeTrack(localVideoSender);
                localVideoSender = null;
            }
            if (localAudioSender != null) {
                peer.pc.removeTrack(localAudioSender);
                localAudioSender = null;
            }
            peer.pc.dispose();
            peer = null;
        }

        // Dispose media stream if it exists
        if (localMS != null) {
            Log.d(TAG, "Disposing local MediaStream.");
            localMS.dispose();
            localMS = null;
        }

        // Dispose capturer, sources, and helpers
        Log.d(TAG, "Stopping and disposing capturer, sources, and helper.");
        if (videoCapturer != null) {
            try {
                videoCapturer.stopCapture();
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping video capturer: " + e.getMessage());
            } finally {
                videoCapturer.dispose();
                videoCapturer = null;
            }
        }
        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }
        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
        }
        if (surfaceTextureHelper != null) {
            surfaceTextureHelper.dispose();
            surfaceTextureHelper = null;
        }

        // Dispose data channel
        if (mDataChannel != null) {
            mDataChannel.unregisterObserver();
            mDataChannel.dispose();
            mDataChannel = null;
        }

        // Dispose PeerConnectionFactory last
        Log.d(TAG, "Disposing PeerConnectionFactory.");
        if (factory != null) {
            factory.dispose();
            factory = null;
        }

        PeerConnectionFactory.stopInternalTracingCapture();
        PeerConnectionFactory.shutdownInternalTracer();

        Log.d(TAG, "PeerConnectionClient cleanup complete.");
    }

    public void start() {
        Log.d(TAG, "PeerConnectionClient started. Waiting for screen capture intent from CallActivity.");
        // We no longer call setCamera() here. Screen capture setup is managed by createDeviceCapture.
    }

    // New helper method to add local tracks to the PeerConnection
    private void addLocalTracksToPeer() {
        if (peer != null && peer.pc != null && localMS != null) {
            Log.d(TAG, "Attempting to add local tracks to PeerConnection.");

            // Ensure old senders are removed before adding new ones, though createDeviceCapture handles this.
            // This is a safeguard if this method is called independently.
            if (localVideoSender != null) {
                peer.pc.removeTrack(localVideoSender);
                localVideoSender = null;
            }
            if (localAudioSender != null) {
                peer.pc.removeTrack(localAudioSender);
                localAudioSender = null;
            }

            // Add video track
            if (!localMS.videoTracks.isEmpty()) {
                VideoTrack videoTrack = localMS.videoTracks.get(0);
                localVideoSender = peer.pc.addTrack(videoTrack, Collections.singletonList(localMS.getId()));
                Log.d(TAG, "Added local video track to peer connection. Sender: " + (localVideoSender != null ? localVideoSender.id() : "null"));
            } else {
                Log.w(TAG, "No video track found in localMS to add.");
            }

            // Add audio track
            if (!localMS.audioTracks.isEmpty()) {
                AudioTrack audioTrack = localMS.audioTracks.get(0);
                localAudioSender = peer.pc.addTrack(audioTrack, Collections.singletonList(localMS.getId()));
                Log.d(TAG, "Added local audio track to peer connection. Sender: " + (localAudioSender != null ? localAudioSender.id() : "null"));
            } else {
                Log.w(TAG, "No audio track found in localMS to add.");
            }

            // If tracks were just added, renegotiation is needed.
            peer.pc.createOffer(peer, pcConstraints);
        } else {
            Log.e(TAG, "Cannot add local tracks to peer: Peer, PeerConnection, or local stream is null. Peer: " + (peer == null) + ", Peer.pc: " + (peer != null && peer.pc == null) + ", localMS: " + (localMS == null));
        }
    }


    public void createDeviceCapture(boolean isScreencast, @Nullable Intent mediaProjectionPermissionResultData) {
        Log.d(TAG, "createDeviceCapture called. isScreencast: " + isScreencast);

        // Store the permission data if we're starting screen share, for later use if peer connects late
        if (isScreencast) {
            this.pendingMediaProjectionPermissionResultData = mediaProjectionPermissionResultData;
        }

        // 1. Stop and dispose of any existing capturer/source/stream components
        if (videoCapturer != null) {
            try {
                videoCapturer.stopCapture();
                videoCapturer.dispose();
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping video capturer: " + e.getMessage());
            } finally {
                videoCapturer = null;
            }
        }
        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }
        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
        }
        if (surfaceTextureHelper != null) {
            surfaceTextureHelper.dispose();
            surfaceTextureHelper = null;
        }

        // 2. Remove existing tracks from PeerConnection and local stream
        // This is crucial for proper renegotiation when switching streams (e.g., stopping share)
        if (peer != null && peer.pc != null) {
            Log.d(TAG, "Removing existing tracks from PeerConnection.");
            if (localVideoSender != null) {
                peer.pc.removeTrack(localVideoSender);
                localVideoSender = null;
            }
            if (localAudioSender != null) {
                peer.pc.removeTrack(localAudioSender);
                localAudioSender = null;
            }
            // Dispose of the local MediaStream if it was previously created
            if (localMS != null) {
                localMS.dispose();
                localMS = null;
            }
            // Trigger renegotiation immediately after removing tracks (if peer exists)
            peer.pc.createOffer(peer, pcConstraints);
        }
        mListener.onRemoveLocalStream(null); // Notify UI to remove local stream preview

        if (isScreencast) {
            if (this.pendingMediaProjectionPermissionResultData == null) {
                Log.e(TAG, "MediaProjection permission data is null for screen capture. Aborting.");
                mListener.onStatusChanged("Failed to start screen capture: Permission data missing.");
                isScreenShareActive = false;
                return;
            }
            Log.d(TAG, "Initializing ScreenCapturerAndroid.");
            videoCapturer = new ScreenCapturerAndroid(
                    this.pendingMediaProjectionPermissionResultData, new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.d(TAG, "Screen sharing stopped by system or user.");
                    mListener.onStatusChanged("Screen sharing stopped.");
                    // You might want to automatically call createDeviceCapture(false, null) here
                    // to formally stop sending the stream.
                    // createDeviceCapture(false, null);
                }
            });

            // Initialize and start the new video capturer
            if (videoCapturer == null) {
                Log.e(TAG, "Video capturer is null after creation attempt. Aborting.");
                mListener.onStatusChanged("Failed to create video capturer.");
                isScreenShareActive = false;
                return;
            }

            videoSource = factory.createVideoSource(videoCapturer.isScreencast());
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase.getEglBaseContext());
            videoCapturer.initialize(surfaceTextureHelper, (Context) mListener, videoSource.getCapturerObserver());

            Utils.ScreenDimensions dimensions = Utils.getScreenDimentions((Context) mListener);
            int fps = Utils.getFps((Context) mListener);

            Log.d(TAG, "Starting capture with: " + dimensions.screenWidth + "x" + dimensions.screenHeight + "@" + fps + "fps");
            videoCapturer.startCapture(dimensions.screenWidth, dimensions.screenHeight, fps);

            // Create new local MediaStream for the screen share video and audio
            localMS = factory.createLocalMediaStream("SCREEN_SHARE_STREAM");
            VideoTrack screenVideoTrack = factory.createVideoTrack("SCREEN_SHARE_VIDEO_TRACK", videoSource);
            screenVideoTrack.setEnabled(true);
            localMS.addTrack(screenVideoTrack);

            audioSource = factory.createAudioSource(new MediaConstraints()); // Microphone audio source
            AudioTrack micAudioTrack = factory.createAudioTrack("MICROPHONE_AUDIO_TRACK", audioSource);
            micAudioTrack.setEnabled(true);
            localMS.addTrack(micAudioTrack);

            isScreenShareActive = true;
            Log.d(TAG, "Local MediaStream for screen share created and active.");

            // *** IMPORTANT: Add tracks to PeerConnection IF it's already established ***
            if (peer != null && peer.pc != null) {
                Log.d(TAG, "Peer connection already exists. Adding local tracks immediately and triggering renegotiation.");
                addLocalTracksToPeer();
            } else {
                Log.d(TAG, "Peer connection not yet established. Tracks will be added when peer connects and triggers Peer creation.");
            }

            mListener.onAddLocalStream(localMS); // Notify listener for local preview
        } else {
            // Logic for stopping screen share (explicitly called with isScreencast = false)
            Log.d(TAG, "Stopping screen capture.");
            isScreenShareActive = false;
            // Existing tracks were already removed at the beginning of this method.
            mListener.onStatusChanged("Screen sharing stopped.");
        }
    }

    // ... (toggleAudio, toggleVideo, createDataChannel, sendDataChannelMessage, helper methods remain largely same) ...
    // Note: toggleVideo now enables/disables the screen share video track.

    public void toggleAudio(boolean enable) {
        if (localMS != null && !localMS.audioTracks.isEmpty()) {
            AudioTrack audioTrack = localMS.audioTracks.get(0);
            audioTrack.setEnabled(enable);
            Log.d(TAG, "Local audio track enabled: " + enable);
        } else {
            Log.w(TAG, "toggleAudio: Local audio track not found or stream not initialized.");
        }
    }

    public void toggleVideo(boolean enable) {
        if (localMS != null && !localMS.videoTracks.isEmpty()) {
            VideoTrack videoTrack = localMS.videoTracks.get(0);
            videoTrack.setEnabled(enable);
            Log.d(TAG, "Local screen share video track enabled: " + enable);
            mListener.onStatusChanged("Screen share video " + (enable ? "enabled" : "disabled"));
        } else {
            Log.w(TAG, "toggleVideo: Local screen share video track not found or stream not initialized.");
        }
    }
    // These methods (setCamera, getVideoCapturer, createCapturer, switchCamera) are not used for screen share only mode.
    // They are kept as stubs to prevent compile errors from CallActivity if it still attempts to call them.
    private void setCamera() { Log.w(TAG, "setCamera() called, but camera capture is not intended."); }
    private VideoCapturer getVideoCapturer() { Log.w(TAG, "getVideoCapturer() called, returning null."); return null; }
    private VideoCapturer createCapturer(CameraEnumerator enumerator, boolean frontFacing) { Log.w(TAG, "createCapturer() called, returning null."); return null; }
    public void switchCamera() { Log.d(TAG, "switchCamera() called. Camera switching is disabled."); mListener.onStatusChanged("Camera switching is disabled. Currently screen sharing."); }

    @Nullable
    public List<CameraEnumerationAndroid.CaptureFormat> getSupportedFormats(@Nullable String cameraId) {
        Camera2Enumerator enumerator = new Camera2Enumerator((Context) mListener);
        return enumerator.getSupportedFormats(cameraId);
    }

    public Size findClosestCaptureFormat(@Nullable String cameraId, int width, int height) {
        List<Size> sizes = new ArrayList<>();
        List<CameraEnumerationAndroid.CaptureFormat> formats = getSupportedFormats(cameraId);
        if (formats != null) {
            for (CameraEnumerationAndroid.CaptureFormat format : formats) {
                sizes.add(new Size(format.width, format.height));
            }
        }
        return CameraEnumerationAndroid.getClosestSupportedSize(sizes, width, height);
    }
}