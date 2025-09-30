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
import java.net.Socket
import kotlinx.coroutines.*

class WifiAwareSubscriber(private val context: Context) {
    private var session: WifiAwareSession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private var peerHandle: PeerHandle? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentNetwork: Network? = null
    
    // Client socket for data transfer
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
    
    fun startSubscribing(
        session: WifiAwareSession,
        onConnectionEstablished: (String) -> Unit,
        onDataReceived: (ByteArray) -> Unit,
        onConnectionLost: () -> Unit
    ) {
        if (!hasRequiredPermissions()) {
            Log.e("WifiAwareSubscriber", "Missing required permissions")
            onConnectionLost.invoke()
            return
        }
        
        this.session = session
        this.onConnectionEstablished = onConnectionEstablished
        this.onDataReceived = onDataReceived
        this.onConnectionLost = onConnectionLost
        
        Log.d("WifiAwareSubscriber", "Starting subscriber")
        
        session.subscribe(SubscribeConfig.Builder()
            .setServiceName("KMPDataTransfer")
            .build(),
            object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                    Log.d("WifiAwareSubscriber", "Subscribing started")
                    subscribeSession = session
                }
                
                override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray?, matchFilter: List<ByteArray>?) {
                    Log.d("WifiAwareSubscriber", "Service discovered: $peerHandle")
                    this@WifiAwareSubscriber.peerHandle = peerHandle
                    subscribeSession?.let { session ->
                        requestConnection(session, peerHandle)
                    }
                }
                
                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    val messageStr = String(message)
                    Log.d("WifiAwareSubscriber", "Received message: $messageStr")
                    
                    if (messageStr == "DATA_CONNECTION_READY") {
                        establishConnection()
                    }
                }
                
                override fun onSessionTerminated() {
                    Log.d("WifiAwareSubscriber", "Subscribe session terminated")
                }
            }, Handler(Looper.getMainLooper()))
    }
    
    private fun requestConnection(session: SubscribeDiscoverySession, peerHandle: PeerHandle) {
        try {
            Log.d("WifiAwareSubscriber", "Requesting connection from peer: $peerHandle")
            session.sendMessage(peerHandle, 1, "REQUEST_DATA_CONNECTION".toByteArray())
        } catch (e: SecurityException) {
            Log.e("WifiAwareSubscriber", "SecurityException sending message: ${e.message}")
            onConnectionLost?.invoke()
        }
    }
    
    private fun establishConnection() {
        val subscribeSession = this.subscribeSession ?: return
        val peerHandle = this.peerHandle ?: return
        
        Log.d("WifiAwareSubscriber", "Establishing client connection")
        
        val networkSpecifier = WifiAwareNetworkSpecifier.Builder(subscribeSession, peerHandle)
            .setPskPassphrase("KMPDataPass123")
            .build()
            
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(networkSpecifier)
            .build()
            
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d("WifiAwareSubscriber", "Network available")
                currentNetwork = network
            }
            
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val wifiAwareInfo = networkCapabilities.transportInfo as? WifiAwareNetworkInfo
                wifiAwareInfo?.let {
                    Log.d("WifiAwareSubscriber", "Connecting to IPv6: ${it.peerIpv6Addr}, Port: ${it.port}")
                    startClientSocket(it.peerIpv6Addr.toString(), it.port)
                }
            }
            
            override fun onLost(network: Network) {
                Log.d("WifiAwareSubscriber", "Network lost")
                onConnectionLost?.invoke()
            }
        }
        
        try {
            networkCallback?.let { callback ->
                connectivityManager?.requestNetwork(networkRequest, callback)
            }
        } catch (e: SecurityException) {
            Log.e("WifiAwareSubscriber", "SecurityException requesting network: ${e.message}")
            onConnectionLost?.invoke()
        }
    }
    
    private fun startClientSocket(ipv6Address: String, port: Int) {
        connectionJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("WifiAwareSubscriber", "Connecting to server at $ipv6Address:$port")
                clientSocket = currentNetwork?.socketFactory?.createSocket(ipv6Address, port)
                Log.d("WifiAwareSubscriber", "Connected to server")
                
                clientSocket?.let { socket ->
                    dataInputStream = DataInputStream(socket.getInputStream())
                    dataOutputStream = DataOutputStream(socket.getOutputStream())
                    
                    onConnectionEstablished?.invoke("Data connection established (Client)")
                    listenForData()
                }
            } catch (e: Exception) {
                Log.e("WifiAwareSubscriber", "Client socket error: ${e.message}")
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
                        
                        Log.d("WifiAwareSubscriber", "Received data: ${data.size} bytes")
                        onDataReceived?.invoke(data)
                    }
                } catch (e: Exception) {
                    Log.e("WifiAwareSubscriber", "Data listening error: ${e.message}")
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
                Log.d("WifiAwareSubscriber", "Sent data: ${data.size} bytes")
            } catch (e: Exception) {
                Log.e("WifiAwareSubscriber", "Failed to send data: ${e.message}")
            }
        }
    }
    
    fun isConnected(): Boolean = clientSocket?.isConnected == true
    
    fun stop() {
        connectionJob?.cancel()
        clientSocket?.close()
        dataInputStream?.close()
        dataOutputStream?.close()
        
        networkCallback?.let { callback ->
            connectivityManager?.unregisterNetworkCallback(callback)
        }
        
        subscribeSession?.close()
        
        clientSocket = null
        dataInputStream = null
        dataOutputStream = null
        subscribeSession = null
        peerHandle = null
        currentNetwork = null
        networkCallback = null
        
        Log.d("WifiAwareSubscriber", "Subscriber stopped")
    }
}
