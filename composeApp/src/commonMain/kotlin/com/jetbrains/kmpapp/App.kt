package com.jetbrains.kmpapp

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.jetbrains.kmpapp.screens.messaging.MessagingScreen
import com.jetbrains.kmpapp.screens.messaging.Message
import com.jetbrains.kmpapp.screens.messaging.WifiAwareService
import kotlin.native.concurrent.ThreadLocal

@Composable
fun App(wifiAwareService: WifiAwareService, permissionsGranted: Boolean = false) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    ) {
        Surface {
            val messages = remember { mutableStateListOf<Message>() }
            val debugLogs = remember { mutableStateListOf<String>() }
            val discoveryStatus = remember { mutableStateOf("Waiting for permissions...") }
            val lastReceivedMessage = remember { mutableStateOf("") }
            val lastSentMessage = remember { mutableStateOf("") }

            // Start discovery only when permissions are granted
            LaunchedEffect(permissionsGranted) {
                if (permissionsGranted) {
                    discoveryStatus.value = "Starting..."
                    debugLogs.add("[UI] Starting discovery...")
                    wifiAwareService.startDiscovery { msg ->
                        debugLogs.add("[App] Message received: $msg")
                        println("[App] Message received: $msg")
                        val message = Message(
                            content = msg,
                            isSent = false,
                            isServiceMessage = msg.startsWith("Service discovered:")
                        )
                        messages.add(message)
                        lastReceivedMessage.value = msg
                    }
                    discoveryStatus.value = "Running"
                    debugLogs.add("[UI] Discovery running.")
                } else {
                    discoveryStatus.value = "Waiting for permissions..."
                    debugLogs.add("[UI] Waiting for user to grant permissions...")
                }
            }
            fun refreshDiscovery() {
                debugLogs.add("[UI] Manual refresh triggered - stopping discovery...")
                discoveryStatus.value = "Stopping..."
                
                // Stop existing discovery first
                wifiAwareService.stopDiscovery()
                debugLogs.add("[UI] Discovery stopped. Restarting...")
                
                discoveryStatus.value = "Restarting..."
                wifiAwareService.startDiscovery { msg ->
                    debugLogs.add("[App] Message received (refresh): $msg")
                    println("[App] Message received (refresh): $msg")
                    val message = Message(
                        content = msg,
                        isSent = false,
                        isServiceMessage = msg.startsWith("Service discovered:")
                    )
                    messages.add(message)
                    lastReceivedMessage.value = msg
                }
                discoveryStatus.value = "Running (refreshed)"
                debugLogs.add("[UI] Discovery restarted successfully.")
            }
            MessagingScreen(
                messages = messages,
                onSend = { msg ->
                    debugLogs.add("[App] Sending message: $msg")
                    println("[App] Sending message: $msg")
                    wifiAwareService.sendMessage(msg)
                    val message = Message(
                        content = msg,
                        isSent = true,
                        isServiceMessage = false
                    )
                    messages.add(message)
                    lastSentMessage.value = msg
                },
                debugLogs = debugLogs,
                discoveryStatus = discoveryStatus.value,
                lastReceivedMessage = lastReceivedMessage.value,
                lastSentMessage = lastSentMessage.value,
                onRefresh = { refreshDiscovery() }
            )
        }
    }   
}
