package com.example.splitterrr.ui.main

import android.app.AlertDialog
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.example.splitterrr.R
import com.example.splitterrr.databinding.ActivityMainBinding
import com.example.splitterrr.utils.ScreenCaptureService
import com.example.splitterrr.utils.webrtc.PeerConnectionClient
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.MediaStream
import org.webrtc.RendererCommon

class MainActivity : AppCompatActivity(), PeerConnectionClient.RtcListener {

    private lateinit var binding: ActivityMainBinding

    private val mSocketAddress = "http://172.22.224.1:4000"
    private var eglBase: EglBase? = null
    private var mediaProjectionManager: MediaProjectionManager? = null
    var mediaProjectionPermissionResultData: Intent? = null


    companion object {
        private const val SCREEN_CAPTURE_REQUEST_CODE: Int = 100
        var peerConnectionClient: PeerConnectionClient? = null
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up toolbar and floating button
        setSupportActionBar(binding.toolbar)

        binding.btnStartSharing.setOnClickListener {
            showSupportDialog()
        }
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
            if (roomId.isNotEmpty()) {
                dialog.dismiss()
                setupConnect(roomId)
            } else {
                Toast.makeText(this, "Invalid Room ID", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun setupConnect(roomId: String) {
        eglBase = EglBase.create()

        binding.localView.init(eglBase?.eglBaseContext, null)
        binding.localView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        binding.localView.setMirror(false)
        binding.localView.setZOrderMediaOverlay(true)
        binding.localView.setEnableHardwareScaler(true) // Enable hardware scaler for efficiency

        peerConnectionClient = PeerConnectionClient(
            roomId,
            this, mSocketAddress, eglBase!!
        )
        peerConnectionClient?.start()

        startScreenCaptureIntent()
    }

    private fun startScreenCaptureIntent() {
        // Request screen capture permission right away when activity starts
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val screenCaptureIntent: Intent = mediaProjectionManager!!.createScreenCaptureIntent()
        startActivityForResult(screenCaptureIntent, SCREEN_CAPTURE_REQUEST_CODE)
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                mediaProjectionPermissionResultData = data

                val serviceIntent = Intent(this, ScreenCaptureService::class.java)
                // Pass the MediaProjection permission result data to the service
                serviceIntent.putExtra("mediaProjectionPermissionResultData", data)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                onStatusChanged("Screen sharing started")
            } else {
                Toast.makeText(this, "Screen capture permission denied.", Toast.LENGTH_SHORT).show()
                onStatusChanged("Screen sharing permission denied. Disconnecting...")
                onBackPressed()
            }
        }
    }

    public override fun onDestroy() {
        if (peerConnectionClient != null) {
            println("1111111 2 CallActivity onDestroy")
            peerConnectionClient!!.onDestroy()
            // Consider nulling out the static reference here if it's not managed by YourApplication
            peerConnectionClient = null
        }

        binding.localView.release()
        eglBase!!.release()

        super.onDestroy()
    }

    override fun onStatusChanged(newStatus: String?) {
        runOnUiThread {
            Toast.makeText(this@MainActivity, newStatus, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDataChannelMessage(message: String?) {
        onStatusChanged("Received message: $message")
    }

    override fun onRemoveLocalStream(localStream: MediaStream?) {
        if (localStream != null && !localStream.videoTracks.isEmpty()) {
            localStream.videoTracks[0].removeSink(binding.localView)
        }
        runOnUiThread {
            binding.localView.clearImage()
            // *** CHANGE 6: Adjust localView to fill parent if local stream is removed and no remote is expected ***
            val params = binding.localView.getLayoutParams() as ConstraintLayout.LayoutParams
            params.width = ConstraintLayout.LayoutParams.MATCH_PARENT
            params.height = ConstraintLayout.LayoutParams.MATCH_PARENT
            params.rightMargin = 0
            params.bottomMargin = 0
            params.topToBottom = ConstraintLayout.LayoutParams.UNSET
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID // Fill vertically
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID // Fill horizontally
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID // Fill horizontally
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID // Fill vertically
            params.horizontalBias = 0.5f
            params.verticalBias = 0.5f
            binding.localView.setLayoutParams(params)
        }
    }

    override fun onAddLocalStream(localStream: MediaStream?) {
        if (localStream != null && !localStream.videoTracks.isEmpty()) {
            val videoTrack = localStream.videoTracks[0]
            videoTrack.setEnabled(true)
            videoTrack.addSink(binding.localView)

            // *** CHANGE 7: Ensure localView is full screen when a local stream (screen share) is added ***
            runOnUiThread {
                val params = binding.localView.getLayoutParams() as ConstraintLayout.LayoutParams
                params.width = ConstraintLayout.LayoutParams.MATCH_PARENT
                params.height = ConstraintLayout.LayoutParams.MATCH_PARENT
                params.rightMargin = 0
                params.topMargin = 0
                params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                params.horizontalBias = 0.5f
                params.verticalBias = 0.5f
                binding.localView.setLayoutParams(params)
            }
        }
    }

    override fun onAddRemoteStream(remoteStream: MediaStream?) {
        if (remoteStream != null && !remoteStream.videoTracks.isEmpty()) {
            // Option A: Just log and ignore. The track is received but not rendered.
            // remoteStream.videoTracks.get(0).removeSink(remoteView); // Already not added, but safe if it was
            remoteStream.videoTracks[0].setEnabled(false) // Attempt to signal to remote peer not to send this video data
        }
        // Handle remote audio if you want to hear it
        if (remoteStream != null && !remoteStream.audioTracks.isEmpty()) {
            remoteStream.audioTracks[0].setEnabled(true) // Keep audio enabled if you want to hear it
            // You might need to add it to a specific audio renderer if not handled by WebRTC default
        }

        runOnUiThread {
            onStatusChanged("Remote peer connected. Sharing screen.")
            val params = binding.localView.getLayoutParams() as ConstraintLayout.LayoutParams
            params.width = ConstraintLayout.LayoutParams.MATCH_PARENT
            params.height = ConstraintLayout.LayoutParams.MATCH_PARENT
            params.rightMargin = 0
            params.topMargin = 0
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            params.horizontalBias = 0.5f
            params.verticalBias = 0.5f
            binding.localView.setLayoutParams(params)
        }
    }

    override fun onRemoveRemoteStream() {
        runOnUiThread {
            onStatusChanged("Remote peer disconnected.")
            // Local view should remain full screen if no remote view is expected
            val params = binding.localView.getLayoutParams() as ConstraintLayout.LayoutParams
            params.width = ConstraintLayout.LayoutParams.MATCH_PARENT
            params.height = ConstraintLayout.LayoutParams.MATCH_PARENT
            params.rightMargin = 0
            params.bottomMargin = 0
            params.topToBottom =
                ConstraintLayout.LayoutParams.UNSET // Reset any previous constraints
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            params.horizontalBias = 0.5f
            params.verticalBias = 0.5f
            binding.localView.setLayoutParams(params)
        }
    }

    override fun onDataChannelStateChange(state: DataChannel.State?) {
        TODO("Not yet implemented")
    }

    override fun onPeersConnectionStatusChange(success: Boolean) {
        TODO("Not yet implemented")
    }
}

