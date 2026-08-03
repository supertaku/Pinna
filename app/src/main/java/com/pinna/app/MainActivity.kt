package com.pinna.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.pinna.app.ui.PinnaApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = (application as PinnaApplication).runtime
        setContent {
            PinnaApp(runtime.controller)
        }
    }
}
