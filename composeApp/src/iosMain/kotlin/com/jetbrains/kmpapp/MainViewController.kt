package com.jetbrains.kmpapp

import androidx.compose.ui.window.ComposeUIViewController
import com.jetbrains.kmpapp.screens.messaging.createWifiAwareService

fun MainViewController() = ComposeUIViewController { 
    App(createWifiAwareService(null))
}
