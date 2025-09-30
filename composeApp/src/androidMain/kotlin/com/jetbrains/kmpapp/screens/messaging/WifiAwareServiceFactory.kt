package com.jetbrains.kmpapp.screens.messaging

import android.content.Context

actual fun createWifiAwareService(context: Any?): WifiAwareService {
    return WifiAwareServiceImpl(context as Context)
}
