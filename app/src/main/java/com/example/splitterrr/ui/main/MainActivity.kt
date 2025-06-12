package com.example.splitterrr.ui.main

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.splitterrr.R
import com.example.splitterrr.databinding.ActivityMainBinding
import com.example.splitterrr.utils.ScreenCaptureService
import com.example.splitterrr.utils.webrtc.SignalingClient
import com.example.splitterrr.utils.webrtc.WebRtcClient
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.webrtc.EglBase

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private lateinit var webRtcClient: WebRtcClient
    private lateinit var floatingStopButton: FloatingActionButton
    private lateinit var signalingClient: SignalingClient
    private lateinit var eglBase: EglBase

    private val SCREEN_CAPTURE_REQUEST_CODE = 1000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up toolbar and floating button
        setSupportActionBar(binding.toolbar)

        floatingStopButton = FloatingActionButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.colorPrimary)
            visibility = View.GONE
        }

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            marginEnd = 24
            bottomMargin = 24
        }

        addContentView(floatingStopButton, params)

        binding.btnStartSharing.setOnClickListener {
            showSupportDialog()
        }

        floatingStopButton.setOnClickListener {
            stopScreenSharing()
        }

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private fun showSupportDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_support, null)
        val input = dialogView.findViewById<EditText>(R.id.etRoomId)
        val btnStart = dialogView.findViewById<Button>(R.id.btnStartScreenShare)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        btnStart.setOnClickListener {
            val roomId = input.text.toString().trim()
            if (roomId == "1234") {
                dialog.dismiss()
                startScreenCapture()
            } else {
                Toast.makeText(this, "Invalid Room ID", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun startScreenCapture() {
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(captureIntent, SCREEN_CAPTURE_REQUEST_CODE)
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                this.startForegroundService(serviceIntent)
            } else {
                this.startService(serviceIntent)
            }
            // Delay WebRTC startup briefly to give time for foreground service to start
            Handler(Looper.getMainLooper()).postDelayed({
                startWebRTC(data)
            }, 500)
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startWebRTC(mediaProjectionPermissionData: Intent) {
        //   EGL context (one per activity)
        eglBase = EglBase.create()

        //  Signaling client
        signalingClient = SignalingClient("ws://172.16.10.219:8080")   // use the constant

        //   Initialise BOTH renderers with the same egl context
        binding.remoteView.init(eglBase.eglBaseContext, null)
        binding.remoteView.setMirror(false)

        binding.localView.init(eglBase.eglBaseContext, null)
        binding.localView.setMirror(true)

        //   Build WebRtcClient (now takes local+remote renderers)
        webRtcClient = WebRtcClient(
            context               = this,
            mediaProjectionIntent = mediaProjectionPermissionData,
            signalingClient       = signalingClient,
            eglBase               = eglBase,
            localRenderer         = binding.localView,
            remoteRenderer        = binding.remoteView,
            listener              = object : WebRtcClient.Listener {
                override fun onPeerConnected() = runOnUiThread {
                    Toast.makeText(this@MainActivity, "Peer connected", Toast.LENGTH_SHORT).show()
                }

                override fun onPeerDisconnected() = runOnUiThread { stopScreenSharing() }

                override fun onError(message: String) = runOnUiThread {
                    Toast.makeText(this@MainActivity, "WebRTC error: $message", Toast.LENGTH_LONG).show()
                }
            }
        )

        //  Start signaling listeners (receiver path) then initiate call (offer)
        webRtcClient.start()
        webRtcClient.initiateCall()

        //  Show floating stop button
        showFloatingStopButton()
    }


    private fun stopScreenSharing() {
        webRtcClient.stop()
        mediaProjection?.stop()
        stopService(Intent(this, ScreenCaptureService::class.java))
        floatingStopButton.visibility = View.GONE
        Toast.makeText(this, "Screen sharing stopped", Toast.LENGTH_SHORT).show()
    }

    private fun showFloatingStopButton() {
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val stopButtonView = inflater.inflate(com.example.splitterrr.R.layout.floating_stop_button, null)

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        layoutParams.x = 50
        layoutParams.y = 100

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager.addView(stopButtonView, layoutParams)

        stopButtonView.setOnClickListener {
            stopScreenSharing()
            windowManager.removeView(stopButtonView)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScreenSharing()
    }
}

