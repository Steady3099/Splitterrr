package com.example.splitterrr.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.splitterrr.R
import com.example.splitterrr.utils.webrtc.CallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        setContentView(R.layout.splash_screen)

        // Navigate to MainActivity after a short delay
//        CoroutineScope(Dispatchers.Main).launch {
//            delay(1500)
//            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
//            finish() // Prevent back navigation to SplashActivity
//        }

        startActivity(Intent(this, CallActivity::class.java))
        finish()
    }
}