package com.jetbrains.kmpapp.screens.messaging

class WifiAwareServiceImpl : WifiAwareService {
    private val manager = IOSWifiAwareManager()

    override fun startDiscovery(onMessageReceived: (String) -> Unit) {
        println("WifiAwareServiceImpl: Starting discovery")
        manager.startDiscovery { message ->
            println("WifiAwareServiceImpl: Message received: $message")
            onMessageReceived(message)
        }
    }

    override fun sendMessage(message: String) {
        println("WifiAwareServiceImpl: Sending message: $message")
        println("WifiAwareServiceImpl: Connection status: ${manager.getConnectionStatus()}")
        manager.sendMessage(message)
    }
    
    override fun isPeerConnected(): Boolean {
        return manager.isPeerConnected()
    }
    
    override fun getConnectionStatus(): String {
        return manager.getConnectionStatus()
    }
    
    // Client-server networking methods (iOS implementation - placeholder)
    override fun startClientServerDiscovery(
        onConnectionEstablished: (String) -> Unit,
        onDataReceived: (ByteArray) -> Unit,
        onConnectionLost: () -> Unit
    ) {
        println("WifiAwareServiceImpl: Client-server discovery not implemented on iOS")
        onConnectionEstablished("iOS: Client-server discovery not available")
    }
    
    override fun sendData(data: ByteArray) {
        println("WifiAwareServiceImpl: Sending data not implemented on iOS")
    }
    
    override fun isDataConnectionActive(): Boolean {
        return false // Not implemented on iOS
    }
    
    override fun getDataConnectionStatus(): String {
        return "iOS: Data connection not supported"
    }
    
    override fun stopDataConnection() {
        println("WifiAwareServiceImpl: Stop data connection not implemented on iOS")
    }
}
