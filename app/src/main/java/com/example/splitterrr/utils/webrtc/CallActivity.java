package com.example.splitterrr.utils.webrtc;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager.LayoutParams;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.example.splitterrr.R;
import com.example.splitterrr.ui.expense.ExpenseListFragment;

import org.webrtc.DataChannel;
import org.webrtc.EglBase;
import org.webrtc.MediaStream;
import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

public class CallActivity extends AppCompatActivity implements PeerConnectionClient.RtcListener {
    private final static String TAG = CallActivity.class.getCanonicalName();

    public static PeerConnectionClient peerConnectionClient;
    private String mSocketAddress;
    private String roomId;
    private boolean audioEnabled = true;

    private static final String[] RequiredPermissions = new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
    protected PermissionChecker permissionChecker = new PermissionChecker();

    private SurfaceViewRenderer localView;
    private LinearLayout callControls;

    // localViewWidth and Height can be kept for aspect ratio calculations if needed,
    // but the actual display size will be different if localView is fullscreen.
    private int localViewWidth = 150;
    private int localViewHeight = 150;
    private EglBase eglBase;

    private static final int SCREEN_CAPTURE_REQUEST_CODE = 100;
    private MediaProjectionManager mediaProjectionManager;
    public static Intent mediaProjectionPermissionResultData; // This will be passed via Intent to service

    private boolean isScreenSharing = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(
                LayoutParams.FLAG_KEEP_SCREEN_ON
                        | LayoutParams.FLAG_DISMISS_KEYGUARD
                        | LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | LayoutParams.FLAG_TURN_SCREEN_ON);
        setContentView(R.layout.activity_main);

        // Disable the native back button (up button)
        if (getSupportActionBar() != null) { // Added null check
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
        }

        // Load default fragment (ExpenseListFragment)
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, new ExpenseListFragment())
                    .commit();
        }

        mSocketAddress = getString(R.string.serverAddress);

        localView = findViewById(R.id.local_view);
        callControls = findViewById(R.id.call_controls);
        Button support = findViewById(R.id.btnStartSharing);

        eglBase = EglBase.create();

        localView.init(eglBase.getEglBaseContext(), null);
        localView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        localView.setMirror(false);
        localView.setZOrderMediaOverlay(true);
        localView.setEnableHardwareScaler(true); // Enable hardware scaler for efficiency

        support.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showSupportDialog();
            }
        });
    }

    private void showSupportDialog() {
        // Inflate the custom layout for the dialog
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_support, null);

        // Get references to the EditText and Button within the dialog's layout
        EditText input = dialogView.findViewById(R.id.etRoomId);
        Button btnStart = dialogView.findViewById(R.id.btnStartScreenShare);

        // Create an AlertDialog.Builder
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        // Set the custom view to the dialog
        builder.setView(dialogView);

        // Make the dialog cancelable by tapping outside or pressing back button
        builder.setCancelable(true);

        // Create the AlertDialog
        AlertDialog dialog = builder.create();

        // Set an OnClickListener for the "Start Screen Share" button
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                roomId = input.getText().toString().trim(); // Get text from EditText and trim whitespace
                if (!roomId.isEmpty()) { // Check if the room ID is not empty
                    checkPermissions();
                    init();

                    dialog.dismiss(); // Dismiss the dialog
                } else {
                    // Show a toast if the room ID is invalid
                    Toast.makeText(CallActivity.this, "Invalid Room ID", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // Show the dialog
        dialog.show();
    }



    private void checkPermissions() {
        permissionChecker.verifyPermissions(this, RequiredPermissions, new PermissionChecker.VerifyPermissionsCallback() {

            @Override
            public void onPermissionAllGranted() {
                // Permissions are granted, proceed with init logic if needed after this
            }

            @Override
            public void onPermissionDeny(String[] permissions) {
                Toast.makeText(CallActivity.this, "Please grant required permissions.", Toast.LENGTH_LONG).show();
                // Optionally finish the activity if permissions are crucial for initial setup
                finish();
            }
        });
    }

    private void init() {
        // Point displaySize = new Point(); // Not needed here if localView will be MATCH_PARENT
        // getWindowManager().getDefaultDisplay().getSize(displaySize);

        // If peerConnectionClient is managed globally via YourApplication, retrieve it here
        // For now, keeping your static access as per your original code.
        peerConnectionClient = new PeerConnectionClient(roomId, this, mSocketAddress,  eglBase);

        if (PermissionChecker.hasPermissions(this, RequiredPermissions)) {
            peerConnectionClient.start();
            // *** CHANGE 2: Automatically start screen capture on init if permissions are granted ***
            // This assumes you want screen sharing to start immediately
            startScreenCaptureIntent();
        }

        ImageButton toggleAudio = findViewById(R.id.toggle_audio);
        toggleAudio.setOnClickListener(v -> {
            peerConnectionClient.toggleAudio(!audioEnabled);
            audioEnabled = !audioEnabled;

            toggleAudio.setImageResource(audioEnabled ? R.drawable.mic_slash_fill : R.drawable.mic_fill);
        });

        ImageButton hangUp = findViewById(R.id.hang_up);
        hangUp.setOnClickListener(v -> {
            if (peerConnectionClient != null) {
                System.out.println("1111111 2 CallActivity onDestroy");
                peerConnectionClient.onDestroy();
                // Consider nulling out the static reference here if it's not managed by YourApplication
                // peerConnectionClient = null;
            }

            localView.release();
            eglBase.release();
            callControls.setVisibility(View.GONE);
        });

        localView.addFrameListener(bitmap -> {
            Log.d(TAG, "localView size: " + bitmap.getWidth() + " " + bitmap.getHeight());
            localViewWidth = bitmap.getWidth();
            localViewHeight = bitmap.getHeight();
            // This is where you might adjust localView's layout if you want it to fill parent after getting dimensions
            // But usually, setting MATCH_PARENT in XML or onAddLocalStream is sufficient.
        }, 1);
    }

    private void startScreenCaptureIntent() {
        // Request screen capture permission right away when activity starts
        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        Intent screenCaptureIntent = mediaProjectionManager.createScreenCaptureIntent();
        startActivityForResult(screenCaptureIntent, SCREEN_CAPTURE_REQUEST_CODE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                mediaProjectionPermissionResultData = data; // Set the static field

                Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
                // Pass the MediaProjection permission result data to the service
                serviceIntent.putExtra("mediaProjectionPermissionResultData", data);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }

                isScreenSharing = true;

                onStatusChanged("Screen sharing started");

            } else {
                Toast.makeText(this, "Screen capture permission denied.", Toast.LENGTH_SHORT).show();
                onStatusChanged("Screen sharing permission denied. Disconnecting...");
                onBackPressed();
            }
        }
        permissionChecker.onRequestPermissionsResult(requestCode, new String[0], new int[0]);
    }

    @Override
    public void onDestroy() {
        System.out.println("1111111 1CallActivity onDestroy " + peerConnectionClient);
        if (peerConnectionClient != null) {
            System.out.println("1111111 2 CallActivity onDestroy");
            peerConnectionClient.onDestroy();
            // Consider nulling out the static reference here if it's not managed by YourApplication
            // peerConnectionClient = null;
        }

        localView.release();
        eglBase.release();

        super.onDestroy();
    }

    @Override
    public void onStatusChanged(final String newStatus) {
        runOnUiThread(() -> Toast.makeText(CallActivity.this, newStatus, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onDataChannelMessage(final String message) {
        onStatusChanged("Received message: " + message);
    }

    @Override
    public void onRemoveLocalStream(MediaStream localStream) {
        Log.d(TAG, "onRemoveLocalStream");
        if (localStream != null && !localStream.videoTracks.isEmpty()) {
            localStream.videoTracks.get(0).removeSink(localView);
        }
        runOnUiThread(() -> {
            localView.clearImage();
            // *** CHANGE 6: Adjust localView to fill parent if local stream is removed and no remote is expected ***
            ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) localView.getLayoutParams();
            params.width = ConstraintLayout.LayoutParams.MATCH_PARENT;
            params.height = ConstraintLayout.LayoutParams.MATCH_PARENT;
            params.rightMargin = 0;
            params.bottomMargin = 0;
            params.topToBottom = ConstraintLayout.LayoutParams.UNSET;
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID; // Fill vertically
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;     // Fill horizontally
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID; // Fill horizontally
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID; // Fill vertically
            params.horizontalBias = 0.5f;
            params.verticalBias = 0.5f;
            localView.setLayoutParams(params);
        });
    }

    @Override
    public void onAddLocalStream(MediaStream localStream) {
        Log.d(TAG, "onAddLocalStream");
        if (localStream != null && !localStream.videoTracks.isEmpty()) {
            VideoTrack videoTrack = localStream.videoTracks.get(0);
            videoTrack.setEnabled(true);
            videoTrack.addSink(localView);

            // *** CHANGE 7: Ensure localView is full screen when a local stream (screen share) is added ***
            runOnUiThread(() -> {
                ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) localView.getLayoutParams();
                params.width = ConstraintLayout.LayoutParams.MATCH_PARENT;
                params.height = ConstraintLayout.LayoutParams.MATCH_PARENT;
                params.rightMargin = 0;
                params.topMargin = 0;
                params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
                params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
                params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
                params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
                params.horizontalBias = 0.5f;
                params.verticalBias = 0.5f;
                localView.setLayoutParams(params);
            });
        }
    }

    @Override
    public void onAddRemoteStream(MediaStream remoteStream) {
        Log.d(TAG, "onAddRemoteStream: Remote stream received. Ignoring video track as per send-only mode.");
        // *** CHANGE 8: DO NOT add remote video to remoteView ***
        if (remoteStream != null && !remoteStream.videoTracks.isEmpty()) {
            // Option A: Just log and ignore. The track is received but not rendered.
            // remoteStream.videoTracks.get(0).removeSink(remoteView); // Already not added, but safe if it was
            remoteStream.videoTracks.get(0).setEnabled(false); // Attempt to signal to remote peer not to send this video data
        }
        // Handle remote audio if you want to hear it
        if (remoteStream != null && !remoteStream.audioTracks.isEmpty()) {
            remoteStream.audioTracks.get(0).setEnabled(true); // Keep audio enabled if you want to hear it
            // You might need to add it to a specific audio renderer if not handled by WebRTC default
        }

        runOnUiThread(() -> {
            onStatusChanged("Remote peer connected. Sharing screen.");
            // No changes to remoteView layout needed as it's GONE
            // Ensure localView remains full screen if it was set to accommodate remoteView
            ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) localView.getLayoutParams();
            params.width = ConstraintLayout.LayoutParams.MATCH_PARENT;
            params.height = ConstraintLayout.LayoutParams.MATCH_PARENT;
            params.rightMargin = 0;
            params.topMargin = 0;
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
            params.horizontalBias = 0.5f;
            params.verticalBias = 0.5f;
            localView.setLayoutParams(params);
            callControls.setVisibility(View.VISIBLE);
        });
    }

    @Override
    public void onRemoveRemoteStream() {
        Log.d(TAG, "onRemoveRemoteStream: Remote stream removed.");
        runOnUiThread(() -> {
            // *** CHANGE 9: No need to clear remoteView image or adjust its layout as it's GONE ***
            // remoteView.clearImage();

            onStatusChanged("Remote peer disconnected.");
            // Local view should remain full screen if no remote view is expected
            ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) localView.getLayoutParams();
            params.width = ConstraintLayout.LayoutParams.MATCH_PARENT;
            params.height = ConstraintLayout.LayoutParams.MATCH_PARENT;
            params.rightMargin = 0;
            params.bottomMargin = 0;
            params.topToBottom = ConstraintLayout.LayoutParams.UNSET; // Reset any previous constraints
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
            params.horizontalBias = 0.5f;
            params.verticalBias = 0.5f;
            localView.setLayoutParams(params);

        });
    }

    @Override
    public void onDataChannelStateChange(DataChannel.State state) {
        if (state == DataChannel.State.OPEN) {
//            dataChannelReady = true;
            onStatusChanged("Data channel ready");
        } else {
//            dataChannelReady = false;
            onStatusChanged("Data channel closed");
        }
    }

    @Override
    public void onPeersConnectionStatusChange(boolean success) {
        runOnUiThread(() -> {
            findViewById(R.id.hang_up).setEnabled(success);
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        permissionChecker.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}