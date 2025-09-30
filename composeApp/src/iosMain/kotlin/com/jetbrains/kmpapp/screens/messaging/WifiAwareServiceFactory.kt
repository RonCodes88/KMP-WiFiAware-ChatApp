package com.jetbrains.kmpapp.screens.messaging

actual fun createWifiAwareService(context: Any?): WifiAwareService {
    return WifiAwareServiceImpl()
}
