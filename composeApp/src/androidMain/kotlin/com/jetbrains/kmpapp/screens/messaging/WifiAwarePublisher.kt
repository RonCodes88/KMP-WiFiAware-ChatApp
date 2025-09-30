package com.jetbrains.kmpapp.screens.messaging

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.*
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.*

class WifiAwarePublisher(private val context: Context) {
    private var session: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var peerHandle: PeerHandle? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentNetwork: Network? = null
    
    // Server socket for data transfer
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var dataOutputStream: DataOutputStream? = null
    private var dataInputStream: DataInputStream? = null
    private var connectionJob: Job? = null
    
    // Callbacks
    private var onConnectionEstablished: ((String) -> Unit)? = null
    private var onDataReceived: ((ByteArray) -> Unit)? = null
    private var onConnectionLost: (() -> Unit)? = null
    
    init {
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }
    
    private fun hasRequiredPermissions(): Boolean {
        val permissions = listOf(
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES
        )
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    fun startPublishing(
        session: WifiAwareSession,
        onConnectionEstablished: (String) -> Unit,
        onDataReceived: (ByteArray) -> Unit,
        onConnectionLost: () -> Unit
    ) {
        if (!hasRequiredPermissions()) {
            Log.e("WifiAwarePublisher", "Missing required permissions")
            onConnectionLost.invoke()
            return
        }
        
        this.session = session
        this.onConnectionEstablished = onConnectionEstablished
        this.onDataReceived = onDataReceived
        this.onConnectionLost = onConnectionLost
        
        Log.d("WifiAwarePublisher", "Starting publisher")
        setupServerSocket()

        session.publish(PublishConfig.Builder()
            .setServiceName("KMPDataTransfer")
            .setServiceSpecificInfo("DataTransferServer".toByteArray())
            .build(),
            object : DiscoverySessionCallback() {
                override fun onPublishStarted(session: PublishDiscoverySession) {
                    Log.d("WifiAwarePublisher", "Publishing started")
                    publishSession = session
                }
                
                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    val messageStr = String(message)
                    Log.d("WifiAwarePublisher", "Received message: $messageStr")
                    
                    if (messageStr == "REQUEST_DATA_CONNECTION") {
                        this@WifiAwarePublisher.peerHandle = peerHandle
                        establishConnection()
                    }
                }
                
                override fun onSessionTerminated() {
                    Log.d("WifiAwarePublisher", "Publish session terminated")
                }
            }, Handler(Looper.getMainLooper()))
    }
    
    private fun setupServerSocket() {
        try {
            serverSocket = ServerSocket(0)
            val port = serverSocket?.localPort ?: 0
            Log.d("WifiAwarePublisher", "Server socket created on port: $port")
        } catch (e: Exception) {
            Log.e("WifiAwarePublisher", "Failed to create server socket: ${e.message}")
        }
    }
    
    private fun establishConnection() {
        val publishSession = this.publishSession ?: return
        val peerHandle = this.peerHandle ?: return
        val port = serverSocket?.localPort ?: return
        
        Log.d("WifiAwarePublisher", "Establishing server connection on port: $port")
        
        val networkSpecifier = WifiAwareNetworkSpecifier.Builder(publishSession, peerHandle)
            .setPskPassphrase("KMPDataPass123")
            .setPort(port)
            .build()
            
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(networkSpecifier)
            .build()
            
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d("WifiAwarePublisher", "Network available")
                currentNetwork = network
                startServerSocket()
            }
            
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val wifiAwareInfo = networkCapabilities.transportInfo as? WifiAwareNetworkInfo
                wifiAwareInfo?.let {
                    Log.d("WifiAwarePublisher", "Server IPv6: ${it.peerIpv6Addr}, Port: ${it.port}")
                }
            }
            
            override fun onLost(network: Network) {
                Log.d("WifiAwarePublisher", "Network lost")
                onConnectionLost?.invoke()
            }
        }
        
        try {
            networkCallback?.let { callback ->
                connectivityManager?.requestNetwork(networkRequest, callback)
            }
            
            // Send connection ready message to subscriber
            publishSession.sendMessage(peerHandle, 2, "DATA_CONNECTION_READY".toByteArray())
        } catch (e: SecurityException) {
            Log.e("WifiAwarePublisher", "SecurityException: ${e.message}")
            onConnectionLost?.invoke()
        }
    }
    
    private fun startServerSocket() {
        connectionJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("WifiAwarePublisher", "Waiting for client connection...")
                clientSocket = serverSocket?.accept()
                Log.d("WifiAwarePublisher", "Client connected")
                
                clientSocket?.let { socket ->
                    dataInputStream = DataInputStream(socket.getInputStream())
                    dataOutputStream = DataOutputStream(socket.getOutputStream())
                    
                    onConnectionEstablished?.invoke("Data connection established (Server)")
                    listenForData()
                }
            } catch (e: Exception) {
                Log.e("WifiAwarePublisher", "Server socket error: ${e.message}")
            }
        }
    }
    
    private fun listenForData() {
        connectionJob?.let { job ->
            CoroutineScope(Dispatchers.IO + job).launch {
                try {
                    while (isActive && clientSocket?.isConnected == true) {
                        val length = dataInputStream?.readInt() ?: break
                        val data = ByteArray(length)
                        dataInputStream?.readFully(data)
                        
                        Log.d("WifiAwarePublisher", "Received data: ${data.size} bytes")
                        onDataReceived?.invoke(data)
                    }
                } catch (e: Exception) {
                    Log.e("WifiAwarePublisher", "Data listening error: ${e.message}")
                    onConnectionLost?.invoke()
                }
            }
        }
    }
    
    fun sendData(data: ByteArray) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                dataOutputStream?.writeInt(data.size)
                dataOutputStream?.write(data)
                dataOutputStream?.flush()
                Log.d("WifiAwarePublisher", "Sent data: ${data.size} bytes")
            } catch (e: Exception) {
                Log.e("WifiAwarePublisher", "Failed to send data: ${e.message}")
            }
        }
    }
    
    fun isConnected(): Boolean = clientSocket?.isConnected == true
    
    fun stop() {
        connectionJob?.cancel()
        clientSocket?.close()
        serverSocket?.close()
        dataInputStream?.close()
        dataOutputStream?.close()
        
        networkCallback?.let { callback ->
            connectivityManager?.unregisterNetworkCallback(callback)
        }
        
        publishSession?.close()
        
        clientSocket = null
        serverSocket = null
        dataInputStream = null
        dataOutputStream = null
        publishSession = null
        peerHandle = null
        currentNetwork = null
        networkCallback = null
        
        Log.d("WifiAwarePublisher", "Publisher stopped")
    }
}
