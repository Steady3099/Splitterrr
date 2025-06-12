package com.example.splitterrr.utils.webrtc

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.concurrent.TimeUnit

class SignalingClient(serverUrl: String) {

    var onOfferReceived: ((SessionDescription) -> Unit)? = null
    var onAnswerReceived: ((SessionDescription) -> Unit)? = null
    var onIceCandidateReceived: ((IceCandidate) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d("SignalingClient", "WebSocket Connected")

            // ✅ Send "register" message with role "initiator"
            val registerMessage = JSONObject()
            registerMessage.put("type", "register")
            registerMessage.put("role", "initiator")

            try {
                webSocket.send(registerMessage.toString())
                Log.d("SignalingClient", "Sent register message: $registerMessage")
            } catch (e: Exception) {
                Log.e("SignalingClient", "Failed to send register message: ${e.message}")
                onError?.invoke("Failed to register with signaling server")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d("SignalingClient", "Received message: $text")
            try {
                val json = JSONObject(text)
                when (json.getString("type")) {
                    "offer" -> {
                        val sdp = SessionDescription(
                            SessionDescription.Type.OFFER,
                            json.getString("sdp")
                        )
                        onOfferReceived?.invoke(sdp)
                    }

                    "answer" -> {
                        val sdp = SessionDescription(
                            SessionDescription.Type.ANSWER,
                            json.getString("sdp")
                        )
                        onAnswerReceived?.invoke(sdp)
                    }

                    "candidate" -> {
                        val candidate = IceCandidate(
                            json.getString("id"),
                            json.getInt("label"),
                            json.getString("candidate")
                        )
                        onIceCandidateReceived?.invoke(candidate)
                    }
                }
            } catch (e: Exception) {
                Log.e("SignalingClient", "Parsing error: ${e.message}")
                onError?.invoke("Failed to parse signaling message")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e("SignalingClient", "WebSocket Error: ${t.message}")
            onError?.invoke("WebSocket failed: ${t.message}")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d("SignalingClient", "WebSocket Closing: $code / $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d("SignalingClient", "WebSocket Closed: $code / $reason")
        }
    }

    private val webSocket: WebSocket

    init {
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, socketListener)
        client.dispatcher.executorService.shutdown()
    }

    fun sendAnswer(answer: SessionDescription) {
        val json = JSONObject()
        json.put("type", "answer")
        json.put("sdp", answer.description)
        send(json.toString())
    }

    fun sendOffer(offer: SessionDescription) {
        val json = JSONObject()
        json.put("type", "offer")
        json.put("sdp", offer.description)
        send(json.toString())
    }


    fun sendIceCandidate(candidate: IceCandidate) {
        val json = JSONObject()
        json.put("type", "candidate")
        json.put("label", candidate.sdpMLineIndex)
        json.put("id", candidate.sdpMid)
        json.put("candidate", candidate.sdp)
        send(json.toString())
    }

    private fun send(message: String) {
        try {
            webSocket.send(message)
            Log.d("SignalingClient", "Sent: $message")
        } catch (e: Exception) {
            Log.e("SignalingClient", "Send failed: ${e.message}")
            onError?.invoke("Send failed: ${e.message}")
        }
    }

    fun close() {
        webSocket.close(1000, "Client disconnected")
    }
}



