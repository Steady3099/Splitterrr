package com.example.splitterrr.ui.main

class NativeBridge {
    companion object {
        init {
            System.loadLibrary("native_lib")
        }

        @JvmStatic
        external fun runModel(prompt: String): String
    }
}
