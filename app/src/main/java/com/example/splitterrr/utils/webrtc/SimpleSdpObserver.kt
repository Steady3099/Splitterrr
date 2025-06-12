package com.example.splitterrr.utils.webrtc

import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
