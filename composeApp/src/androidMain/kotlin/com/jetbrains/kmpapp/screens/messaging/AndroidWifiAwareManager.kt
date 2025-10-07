package com.jetbrains.kmpapp.screens.messaging

import android.Manifest
import android.content.Context
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.WifiAwareSession
import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeDiscoverySession
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.net.wifi.aware.AttachCallback
import android.provider.Settings
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.Network
import java.net.ServerSocket
import java.net.Socket
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.delay
class AndroidWifiAwareManager(private val context: Context) {
    private var wifiAwareManager: WifiAwareManager? = null
    private var session: WifiAwareSession? = null
    private var publishSession: DiscoverySession? = null
    private var subscribeSession: DiscoverySession? = null
    private var peerHandle: PeerHandle? = null
    private var messageCallback: ((String) -> Unit)? = null
    private var attachmentCallback: ((ByteArray, String) -> Unit)? = null

    // Network connection for socket-based communication
    private var connectivityManager: ConnectivityManager? = null
    private var network: Network? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isPublisher = false

    init {
        wifiAwareManager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    private fun hasRequiredPermissions(): Boolean {
        val perms = listOf(
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        return perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }
    
    private fun getDeviceName(): String {
        return try {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
                ?: Settings.Secure.getString(context.contentResolver, "bluetooth_name")
                ?: android.os.Build.MODEL
                ?: "Unknown Device"
        } catch (e: Exception) {
            Log.w("WifiAware", "Could not get device name: ${e.message}")
            android.os.Build.MODEL ?: "Unknown Device"
        }
    }

    fun startDiscovery(onMessageReceived: (String) -> Unit) {
        messageCallback = onMessageReceived
        if (!hasRequiredPermissions()) {
            Log.e("WifiAware", "Missing required permissions")
            return
        }
        try {
            wifiAwareManager?.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    this@AndroidWifiAwareManager.session = session
                    Log.d("WifiAware", "Attached to WiFi Aware session")
                    
                    // Start publishing (advertising) the service
                    val deviceName = getDeviceName()
                    Log.d("WifiAware", "Publishing service with device name: $deviceName")
                    session.publish(PublishConfig.Builder()
                        .setServiceName("KMPChat")
                        .setServiceSpecificInfo(deviceName.toByteArray())
                        .build(), 
                        object : DiscoverySessionCallback() {
                            override fun onPublishStarted(session: PublishDiscoverySession) {
                                publishSession = session
                                isPublisher = true
                                Log.d("WifiAware", "Publishing started (will act as server)")

                                // Publisher acts as server - create server socket
                                scope.launch {
                                    createServerSocket()
                                }
                            }
                            
                            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                                this@AndroidWifiAwareManager.peerHandle = peerHandle
                                val receivedMessage = String(message)
                                Log.d("WifiAware", "Message received via publish session: $receivedMessage")
                                handleIncomingMessage(receivedMessage)

                                // Publisher also needs to request network when it receives a message
                                if (network == null && publishSession != null) {
                                    requestNetworkConnectionAsPublisher(peerHandle, publishSession as? PublishDiscoverySession)
                                }
                            }
                            
                            override fun onMessageSendSucceeded(messageId: Int) {
                                Log.d("WifiAware", "Message sent successfully via publish session, ID: $messageId")
                            }
                            
                            override fun onMessageSendFailed(messageId: Int) {
                                Log.e("WifiAware", "Message send failed via publish session, ID: $messageId")
                            }
                            
                            override fun onSessionConfigUpdated() {
                                Log.d("WifiAware", "Session config updated")
                            }
                            
                            override fun onSessionConfigFailed() {
                                Log.e("WifiAware", "Session config failed")
                            }
                            
                            override fun onSessionTerminated() {
                                Log.d("WifiAware", "Publish session terminated")
                            }
                        }, Handler(Looper.getMainLooper()))
                    
                    // Start subscribing (discovering) the service
                    session.subscribe(SubscribeConfig.Builder()
                        .setServiceName("KMPChat")
                        .build(),
                        object : DiscoverySessionCallback() {
                            override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                                subscribeSession = session
                                Log.d("WifiAware", "Subscribing started")
                            }
                            
                            override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray?, matchFilter: List<ByteArray>?) {
                                this@AndroidWifiAwareManager.peerHandle = peerHandle
                                Log.d("WifiAware", "Service discovered from peer: $peerHandle")
                                Log.d("WifiAware", "Peer handle stored for messaging")
                                serviceSpecificInfo?.let {
                                    val peerDeviceName = String(it)
                                    Log.d("WifiAware", "Service info: $peerDeviceName")
                                    handleIncomingMessage("Device discovered: $peerDeviceName")
                                }

                                // Request network connection for socket-based communication
                                requestNetworkConnection(peerHandle, subscribeSession as? SubscribeDiscoverySession)
                            }
                            
                            override fun onServiceDiscoveredWithinRange(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray?, matchFilter: List<ByteArray>?, distanceMm: Int) {
                                this@AndroidWifiAwareManager.peerHandle = peerHandle
                                Log.d("WifiAware", "Service discovered within range: ${distanceMm}mm")
                            }
                            
                            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                                val receivedMessage = String(message)
                                Log.d("WifiAware", "Message received via subscribe session: $receivedMessage")
                                handleIncomingMessage(receivedMessage)
                            }
                            
                            override fun onMessageSendSucceeded(messageId: Int) {
                                Log.d("WifiAware", "Message sent successfully via subscribe session, ID: $messageId")
                            }
                            
                            override fun onMessageSendFailed(messageId: Int) {
                                Log.e("WifiAware", "Message send failed via subscribe session, ID: $messageId")
                            }
                            
                            override fun onSessionConfigUpdated() {
                                Log.d("WifiAware", "Subscribe session config updated")
                            }
                            
                            override fun onSessionConfigFailed() {
                                Log.e("WifiAware", "Subscribe session config failed")
                            }
                            
                            override fun onSessionTerminated() {
                                Log.d("WifiAware", "Subscribe session terminated")
                            }
                        }, Handler(Looper.getMainLooper()))
                }
                override fun onAttachFailed() {
                    Log.e("WifiAware", "Attach failed")
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            Log.e("WifiAware", "SecurityException: ${e.message}")
        }
    }

    fun sendMessage(message: String) {
        val peer = peerHandle ?: run {
            Log.e("WifiAware", "No peer handle available - cannot send message")
            return
        }
        
        val subscribeSession = subscribeSession as? SubscribeDiscoverySession
        if (subscribeSession == null) {
            Log.e("WifiAware", "No subscribe session available - cannot send message")
            return
        }
        
        Log.d("WifiAware", "Sending message: $message to peer: $peer")
        
        val messageBytes = message.toByteArray()
        val messageId = System.currentTimeMillis().toInt()
        
        try {
            subscribeSession.sendMessage(peer, messageId, messageBytes)
            Log.d("WifiAware", "Message sent: $message")
        } catch (e: Exception) {
            Log.e("WifiAware", "Failed to send message: ${e.message}")
        }
    }
    
    private fun handleIncomingMessage(message: String) {
        Log.d("WifiAware", "Handling incoming message: $message")
        messageCallback?.invoke(message)
    }
    
    fun isPeerConnected(): Boolean {
        return peerHandle != null && (publishSession != null || subscribeSession != null)
    }
    
    fun getConnectionStatus(): String {
        return when {
            peerHandle == null -> "No peer discovered"
            publishSession == null && subscribeSession == null -> "No active sessions"
            else -> "Connected to peer: $peerHandle"
        }
    }
    
    fun stopDiscovery() {
        scope.cancel()
        clientSocket?.close()
        serverSocket?.close()
        publishSession?.close()
        subscribeSession?.close()
        session?.close()
        publishSession = null
        subscribeSession = null
        session = null
        peerHandle = null
        network = null
        clientSocket = null
        serverSocket = null
        Log.d("WifiAware", "Discovery stopped")
    }

    private fun requestNetworkConnection(peerHandle: PeerHandle, discoverySession: SubscribeDiscoverySession?) {
        if (discoverySession == null) {
            Log.e("WifiAware", "Cannot request network: no discovery session")
            return
        }

        Log.d("WifiAware", "Requesting network connection to peer (as subscriber/client)")

        val networkSpecifier = WifiAwareNetworkSpecifier.Builder(discoverySession, peerHandle)
            .build()

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(networkSpecifier)
            .build()

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d("WifiAware", "Network available (subscriber): $network")
                this@AndroidWifiAwareManager.network = network

                // Subscriber (client) connects to publisher (server)
                scope.launch {
                    connectAsClient(network)
                }
            }

            override fun onLost(network: Network) {
                Log.d("WifiAware", "Network lost: $network")
                this@AndroidWifiAwareManager.network = null
            }

            override fun onUnavailable() {
                Log.e("WifiAware", "Network unavailable (subscriber)")
            }
        }

        connectivityManager?.requestNetwork(networkRequest, networkCallback, Handler(Looper.getMainLooper()))
    }

    private fun requestNetworkConnectionAsPublisher(peerHandle: PeerHandle, discoverySession: PublishDiscoverySession?) {
        if (discoverySession == null) {
            Log.e("WifiAware", "Cannot request network: no publish session")
            return
        }

        Log.d("WifiAware", "Requesting network connection to peer (as publisher/server)")

        val networkSpecifier = WifiAwareNetworkSpecifier.Builder(discoverySession, peerHandle)
            .build()

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(networkSpecifier)
            .build()

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d("WifiAware", "Network available (publisher): $network")
                this@AndroidWifiAwareManager.network = network
                Log.d("WifiAware", "Publisher network ready, server socket should accept connection")
            }

            override fun onLost(network: Network) {
                Log.d("WifiAware", "Network lost (publisher): $network")
                this@AndroidWifiAwareManager.network = null
            }

            override fun onUnavailable() {
                Log.e("WifiAware", "Network unavailable (publisher)")
            }
        }

        connectivityManager?.requestNetwork(networkRequest, networkCallback, Handler(Looper.getMainLooper()))
    }

    private suspend fun createServerSocket() {
        withContext(Dispatchers.IO) {
            try {
                // Wait for network to be available
                var attempts = 0
                while (network == null && attempts < 50) {
                    delay(100)
                    attempts++
                }

                val availableNetwork = network
                if (availableNetwork == null) {
                    Log.e("WifiAware", "Network not available after waiting, cannot create server socket")
                    return@withContext
                }

                Log.d("WifiAware", "Creating server socket on port 8888 with network $availableNetwork...")
                // Create server socket bound to any address on port 8888
                // The network binding happens via the client connection using the network's socket factory
                serverSocket = ServerSocket(8888)
                Log.d("WifiAware", "Server socket created, waiting for connections...")

                // Accept incoming connection (blocking)
                val socket = serverSocket?.accept()
                if (socket != null) {
                    clientSocket = socket
                    Log.d("WifiAware", "Client connected to server")
                    handleIncomingMessage("Connection established")

                    // Start listening for incoming data
                    listenForData(socket)
                }
            } catch (e: Exception) {
                Log.e("WifiAware", "Server socket failed: ${e.message}", e)
            }
        }
    }

    private suspend fun connectAsClient(network: Network) {
        withContext(Dispatchers.IO) {
            try {
                Log.d("WifiAware", "Connecting as client...")
                // Use IPv6 link-local address (standard for WiFi Aware)
                val socket = network.socketFactory.createSocket("fe80::1", 8888)
                clientSocket = socket
                Log.d("WifiAware", "Client connected successfully")
                handleIncomingMessage("Connection established")

                // Start listening for incoming data
                listenForData(socket)
            } catch (e: Exception) {
                Log.e("WifiAware", "Client connection failed: ${e.message}")
            }
        }
    }

    private suspend fun listenForData(socket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                val inputStream = DataInputStream(socket.getInputStream())

                while (socket.isConnected && !socket.isClosed) {
                    // Read message type (1 byte: 0=text, 1=attachment)
                    val messageType = inputStream.readByte().toInt()

                    when (messageType) {
                        0 -> {
                            // Text message
                            val length = inputStream.readInt()
                            val messageBytes = ByteArray(length)
                            inputStream.readFully(messageBytes)
                            val message = String(messageBytes)
                            Log.d("WifiAware", "Received text via socket: $message")
                            handleIncomingMessage(message)
                        }
                        1 -> {
                            // Attachment
                            val typeLength = inputStream.readInt()
                            val typeBytes = ByteArray(typeLength)
                            inputStream.readFully(typeBytes)
                            val attachmentType = String(typeBytes)

                            val dataLength = inputStream.readInt()
                            val data = ByteArray(dataLength)
                            inputStream.readFully(data)

                            Log.d("WifiAware", "Received attachment via socket: type=$attachmentType, size=$dataLength")
                            attachmentCallback?.invoke(data, attachmentType)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("WifiAware", "Error reading from socket: ${e.message}")
            }
        }
    }

    fun sendAttachment(data: ByteArray, type: String) {
        scope.launch {
            try {
                val socket = clientSocket
                if (socket == null || socket.isClosed) {
                    Log.e("WifiAware", "No socket available for sending attachment")
                    return@launch
                }

                val outputStream = DataOutputStream(socket.getOutputStream())

                // Write message type (1 = attachment)
                outputStream.writeByte(1)

                // Write attachment type
                val typeBytes = type.toByteArray()
                outputStream.writeInt(typeBytes.size)
                outputStream.write(typeBytes)

                // Write attachment data
                outputStream.writeInt(data.size)
                outputStream.write(data)
                outputStream.flush()

                Log.d("WifiAware", "Attachment sent: type=$type, size=${data.size}")
            } catch (e: Exception) {
                Log.e("WifiAware", "Failed to send attachment: ${e.message}")
            }
        }
    }

    fun setAttachmentCallback(callback: (ByteArray, String) -> Unit) {
        this.attachmentCallback = callback
    }
}
