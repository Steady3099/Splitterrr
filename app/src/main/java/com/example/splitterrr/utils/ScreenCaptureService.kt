package com.example.splitterrr.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.splitterrr.R
import com.example.splitterrr.ui.main.MainActivity

class ScreenCaptureService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start the foreground service
        startForeground(1, createNotification())

        // *** CHANGE 1: Retrieve the MediaProjection permission result data from the incoming Intent ***
        var mediaProjectionPermissionResultData: Intent? = null
        if (intent != null && intent.hasExtra("mediaProjectionPermissionResultData")) {
            mediaProjectionPermissionResultData =
                intent.getParcelableExtra("mediaProjectionPermissionResultData")
        }

        if (mediaProjectionPermissionResultData == null) {
            Log.e(
                TAG,
                "MediaProjection permission result data is null. Cannot start screen capture."
            )
            // Stop the service if crucial data is missing
            stopSelf()
            return START_NOT_STICKY
        }

        Log.d(TAG, "ScreenCaptureService: Starting screen capture with PeerConnectionClient.")
        // *** CHANGE 2: Pass the retrieved data directly to createDeviceCapture ***
        // Ensure PeerConnectionClient is not null before calling its method
        if (MainActivity.peerConnectionClient != null) {
            MainActivity.peerConnectionClient?.createDeviceCapture(
                true,
                mediaProjectionPermissionResultData
            )
        } else {
            Log.e(
                TAG,
                "PeerConnectionClient is null in ScreenCaptureService. Cannot start screen capture."
            )
            stopSelf() // Stop the service if PeerConnectionClient isn't ready
        }

        return START_STICKY
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            )
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            val notificationManager = getSystemService(
                NotificationManager::class.java
            )
            notificationManager?.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Capture Service")
            .setContentText("Capturing the screen...")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Ensure this drawable exists
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "ScreenCaptureService onDestroy.")
        // If createDeviceCapture(false, null) is not called when stopping,
        // you might want to add logic here to explicitly stop the screen capturer
        // via PeerConnectionClient if it's still active.
        // However, the current design assumes CallActivity handles stopping via the button.
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    companion object {
        const val CHANNEL_ID: String = "ScreenCaptureChannel"
        private val TAG: String =
            ScreenCaptureService::class.java.canonicalName // Add a TAG for logging
    }
}
