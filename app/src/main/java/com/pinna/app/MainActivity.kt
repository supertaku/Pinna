package com.pinna.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.pinna.app.runtime.PinnaRuntime
import com.pinna.app.ui.PinnaApp

class MainActivity : ComponentActivity() {
    private lateinit var runtime: PinnaRuntime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = PinnaRuntime(this)
        setContent {
            PinnaApp(runtime.controller)
        }
    }

    override fun onDestroy() {
        runtime.release()
        super.onDestroy()
    }
}
