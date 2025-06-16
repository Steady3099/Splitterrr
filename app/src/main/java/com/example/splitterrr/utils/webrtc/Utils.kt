package com.example.splitterrr.utils.webrtc

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager

object Utils {
    fun getScreenDimentions(context: Context): ScreenDimensions {
        val displayMetrics = DisplayMetrics()
        val windowManager = (context).getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        return ScreenDimensions(screenWidth, screenHeight)
    }

    fun getFps(context: Context): Int {
        val display =
            ((context).getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        val refreshRate = display.refreshRate

        val fps = if (refreshRate >= 90) {
            60 // Use 60 FPS for high refresh rate displays
        } else if (refreshRate >= 60) {
            30 // Use 30 FPS for standard displays
        } else {
            15 // Use 15 FPS for lower refresh rate displays
        }

        return fps
    }

    class ScreenDimensions(var screenWidth: Int, var screenHeight: Int)
}