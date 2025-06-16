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

class SignalingClient(private val serverUrl: String) {

    var onOfferReceived: ((SessionDescription) -> Unit)? = null
    var onAnswerReceived: ((SessionDescription) -> Unit)? = null
    var onIceCandidateReceived: ((IceCandidate) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val TAG = "SignalingClient"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS) // Added connect timeout
        .build()

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket Connected to: $serverUrl")

            // Send "register" message with role "initiator"
            val registerMessage = JSONObject()
            registerMessage.put("type", "register")
            registerMessage.put("role", "initiator") // Assuming Android app is the initiator

            try {
                webSocket.send(registerMessage.toString())
                Log.d(TAG, "Sent register message: $registerMessage")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send register message: ${e.message}", e)
                onError?.invoke("Failed to register with signaling server")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Received message: $text")
            try {
                val json = JSONObject(text)
                when (json.getString("type")) {
                    "offer" -> {
                        val sdp = SessionDescription(
                            SessionDescription.Type.OFFER,
                            json.getString("sdp")
                        )
                        Log.d(TAG, "Parsed Offer. Invoking onOfferReceived callback.")
                        onOfferReceived?.invoke(sdp)
                    }

                    "answer" -> {
                        val sdp = SessionDescription(
                            SessionDescription.Type.ANSWER,
                            json.getString("sdp")
                        )
                        Log.d(TAG, "Parsed Answer. Invoking onAnswerReceived callback.")
                        onAnswerReceived?.invoke(sdp)
                    }

                    "candidate" -> {
                        val candidate = IceCandidate(
                            json.getString("id"),
                            json.getInt("label"),
                            json.getString("candidate")
                        )
                        Log.d(TAG, "Parsed ICE Candidate. Invoking onIceCandidateReceived callback.")
                        onIceCandidateReceived?.invoke(candidate)
                    }
                    else -> {
                        Log.w(TAG, "Received unknown message type: ${json.getString("type")}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Parsing error: ${e.message}. Message: $text", e)
                onError?.invoke("Failed to parse signaling message")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val responseCode = response?.code ?: -1
            val responseMessage = response?.message ?: "N/A"
            Log.e(TAG, "WebSocket Error: ${t.message}. Response: $responseCode - $responseMessage", t)
            onError?.invoke("WebSocket failed: ${t.message} (Code: $responseCode)")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket Closing: Code $code / Reason: $reason")
            // No need to explicitly close here, OkHttp handles it if onClosing is called.
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket Closed: Code $code / Reason: $reason")
        }
    }

    private val webSocket: WebSocket

    init {
        Log.d(TAG, "init: Initializing SignalingClient with URL: $serverUrl")
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, socketListener) // This line initiates the WebSocket connection
        Log.d(TAG, "init: WebSocket client created.")
    }

    fun sendAnswer(answer: SessionDescription) {
        val json = JSONObject()
        json.put("type", "answer")
        json.put("sdp", answer.description)
        Log.d(TAG, "sendAnswer: Sending answer. SDP: ${answer.description.take(100)}...")
        send(json.toString())
    }

    fun sendOffer(offer: SessionDescription) {
        val json = JSONObject()
        json.put("type", "offer")
        json.put("sdp", offer.description)
        Log.d(TAG, "sendOffer: Sending offer. SDP: ${offer.description.take(100)}...")
        send(json.toString())
    }

    fun sendIceCandidate(candidate: IceCandidate) {
        val json = JSONObject()
        json.put("type", "candidate")
        json.put("label", candidate.sdpMLineIndex)
        json.put("id", candidate.sdpMid)
        json.put("candidate", candidate.sdp)
        Log.d(TAG, "sendIceCandidate: Sending ICE candidate. SdpMid: ${candidate.sdpMid}, Label: ${candidate.sdpMLineIndex}, Candidate: ${candidate.sdp.take(50)}...")
        send(json.toString())
    }

    private fun send(message: String) {
        try {
            val sent = webSocket.send(message)
            if (sent) {
                Log.d(TAG, "Sent message successfully: ${message.take(100)}...")
            } else {
                Log.e(TAG, "Failed to send message: ${message.take(100)}. WebSocket not ready?")
                onError?.invoke("Failed to send message: WebSocket not ready.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message: ${e.message}. Message: ${message.take(100)}...", e)
            onError?.invoke("Send failed: ${e.message}")
        }
    }

    fun close() {
        Log.d(TAG, "close: Closing WebSocket connection.")
        webSocket.close(1000, "Client disconnected")
        // It's generally good to shutdown the client's executor service when the app exits or client is no longer needed
        client.dispatcher.executorService.shutdown()
        Log.d(TAG, "close: WebSocket closed and client executor shutdown.")
    }
}