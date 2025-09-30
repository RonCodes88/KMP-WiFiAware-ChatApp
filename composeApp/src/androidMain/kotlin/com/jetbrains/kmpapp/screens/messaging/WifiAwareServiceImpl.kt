package com.jetbrains.kmpapp.screens.messaging

import android.content.Context
import android.util.Log

class WifiAwareServiceImpl(private val context: Context) : WifiAwareService {
    private val manager = AndroidWifiAwareManager(context)
    private val coordinator = WifiAwareCoordinator(context)

    override fun startDiscovery(onMessageReceived: (String) -> Unit) {
        Log.d("WifiAwareServiceImpl", "Starting discovery")
        manager.startDiscovery { message ->
            Log.d("WifiAwareServiceImpl", "Message received: $message")
            onMessageReceived(message)
        }
    }

    override fun sendMessage(message: String) {
        Log.d("WifiAwareServiceImpl", "Sending message: $message")
        Log.d("WifiAwareServiceImpl", "Connection status: ${manager.getConnectionStatus()}")
        manager.sendMessage(message)
    }
    
    override fun isPeerConnected(): Boolean {
        return manager.isPeerConnected()
    }
    
    override fun getConnectionStatus(): String {
        return manager.getConnectionStatus()
    }
    
    // Client-server networking methods using the coordinator
    override fun startClientServerDiscovery(
        onConnectionEstablished: (String) -> Unit,
        onDataReceived: (ByteArray) -> Unit,
        onConnectionLost: () -> Unit
    ) {
        Log.d("WifiAwareServiceImpl", "Starting client-server discovery")
        coordinator.startClientServerDiscovery(
            onConnectionEstablished = onConnectionEstablished,
            onDataReceived = onDataReceived,
            onConnectionLost = onConnectionLost
        )
    }
    
    override fun sendData(data: ByteArray) {
        Log.d("WifiAwareServiceImpl", "Sending data: ${data.size} bytes")
        coordinator.sendData(data)
    }
    
    override fun isDataConnectionActive(): Boolean {
        return coordinator.isDataConnectionActive()
    }
    
    override fun getDataConnectionStatus(): String {
        return coordinator.getDataConnectionStatus()
    }
    
    override fun stopDataConnection() {
        Log.d("WifiAwareServiceImpl", "Stopping data connection")
        coordinator.stopDataConnection()
    }
}
