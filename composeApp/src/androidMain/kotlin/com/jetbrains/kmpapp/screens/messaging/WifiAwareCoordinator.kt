package com.jetbrains.kmpapp.screens.messaging

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.net.wifi.aware.AttachCallback
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

class WifiAwareCoordinator(private val context: Context) {
    private var wifiAwareManager: WifiAwareManager? = null
    private var session: WifiAwareSession? = null
    private var publisher: WifiAwarePublisher? = null
    private var subscriber: WifiAwareSubscriber? = null
    
    // Callbacks
    private var onConnectionEstablished: ((String) -> Unit)? = null
    private var onDataReceived: ((ByteArray) -> Unit)? = null
    private var onConnectionLost: (() -> Unit)? = null
    
    init {
        wifiAwareManager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
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
    
    fun startClientServerDiscovery(
        onConnectionEstablished: (String) -> Unit,
        onDataReceived: (ByteArray) -> Unit,
        onConnectionLost: () -> Unit
    ) {
        if (!hasRequiredPermissions()) {
            Log.e("WifiAwareCoordinator", "Missing required permissions")
            onConnectionLost.invoke()
            return
        }
        
        this.onConnectionEstablished = onConnectionEstablished
        this.onDataReceived = onDataReceived
        this.onConnectionLost = onConnectionLost
        
        Log.d("WifiAwareCoordinator", "Starting client-server discovery")
        
        try {
            wifiAwareManager?.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                this@WifiAwareCoordinator.session = session
                Log.d("WifiAwareCoordinator", "Attached to WiFi Aware session")
                
                // Start both publisher and subscriber
                startPublisherAndSubscriber(session)
            }
            
            override fun onAttachFailed() {
                Log.e("WifiAwareCoordinator", "Attach failed")
            }
        }, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            Log.e("WifiAwareCoordinator", "SecurityException attaching to WiFi Aware: ${e.message}")
            onConnectionLost.invoke()
        }
    }
    
    private fun startPublisherAndSubscriber(session: WifiAwareSession) {
        Log.d("WifiAwareCoordinator", "Starting publisher and subscriber")
        
        publisher = WifiAwarePublisher(context).apply {
            startPublishing(
                session = session,
                onConnectionEstablished = { status ->
                    Log.d("WifiAwareCoordinator", "Publisher connection established: $status")
                    onConnectionEstablished?.invoke(status)
                },
                onDataReceived = { data ->
                    Log.d("WifiAwareCoordinator", "Publisher received data: ${data.size} bytes")
                    onDataReceived?.invoke(data)
                },
                onConnectionLost = {
                    Log.d("WifiAwareCoordinator", "Publisher connection lost")
                    onConnectionLost?.invoke()
                }
            )
        }
        
        subscriber = WifiAwareSubscriber(context).apply {
            startSubscribing(
                session = session,
                onConnectionEstablished = { status ->
                    Log.d("WifiAwareCoordinator", "Subscriber connection established: $status")
                    onConnectionEstablished?.invoke(status)
                },
                onDataReceived = { data ->
                    Log.d("WifiAwareCoordinator", "Subscriber received data: ${data.size} bytes")
                    onDataReceived?.invoke(data)
                },
                onConnectionLost = {
                    Log.d("WifiAwareCoordinator", "Subscriber connection lost")
                    onConnectionLost?.invoke()
                }
            )
        }
    }
    
    fun sendData(data: ByteArray) {
        // Try publisher first, then subscriber
        when {
            publisher?.isConnected() == true -> {
                Log.d("WifiAwareCoordinator", "Sending data via publisher")
                publisher?.sendData(data)
            }
            subscriber?.isConnected() == true -> {
                Log.d("WifiAwareCoordinator", "Sending data via subscriber")
                subscriber?.sendData(data)
            }
            else -> Log.w("WifiAwareCoordinator", "No active connection to send data")
        }
    }
    
    fun isDataConnectionActive(): Boolean {
        return publisher?.isConnected() == true || subscriber?.isConnected() == true
    }
    
    fun getDataConnectionStatus(): String {
        return when {
            publisher?.isConnected() == true -> "Connected as server"
            subscriber?.isConnected() == true -> "Connected as client"
            session != null -> "Discovery active, waiting for connection"
            else -> "No data connection"
        }
    }
    
    fun stopDataConnection() {
        Log.d("WifiAwareCoordinator", "Stopping data connection")
        
        publisher?.stop()
        subscriber?.stop()
        session?.close()
        
        publisher = null
        subscriber = null
        session = null
        
        Log.d("WifiAwareCoordinator", "Data connection stopped")
    }
}
