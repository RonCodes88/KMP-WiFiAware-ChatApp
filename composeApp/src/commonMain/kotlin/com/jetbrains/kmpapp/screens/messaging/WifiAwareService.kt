package com.jetbrains.kmpapp.screens.messaging

interface WifiAwareService {
    fun startDiscovery(onMessageReceived: (String) -> Unit)
    fun sendMessage(message: String)
    fun isPeerConnected(): Boolean
    fun getConnectionStatus(): String
    
    // Client-server networking methods
    fun startClientServerDiscovery(
        onConnectionEstablished: (String) -> Unit,
        onDataReceived: (ByteArray) -> Unit,
        onConnectionLost: () -> Unit
    )
    fun sendData(data: ByteArray)
    fun isDataConnectionActive(): Boolean
    fun getDataConnectionStatus(): String
    fun stopDataConnection()
}

