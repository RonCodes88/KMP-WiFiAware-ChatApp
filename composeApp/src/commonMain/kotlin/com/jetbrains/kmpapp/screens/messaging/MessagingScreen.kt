package com.jetbrains.kmpapp.screens.messaging

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add

data class Message(
    val content: String,
    val isSent: Boolean,
    val isServiceMessage: Boolean = false,
    val attachmentData: ByteArray? = null,
    val attachmentType: String? = null
) {
    val isAttachment: Boolean
        get() = attachmentData != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as Message

        if (content != other.content) return false
        if (isSent != other.isSent) return false
        if (isServiceMessage != other.isServiceMessage) return false
        if (attachmentData != null) {
            if (other.attachmentData == null) return false
            if (!attachmentData.contentEquals(other.attachmentData)) return false
        } else if (other.attachmentData != null) return false
        if (attachmentType != other.attachmentType) return false

        return true
    }

    override fun hashCode(): Int {
        var result = content.hashCode()
        result = 31 * result + isSent.hashCode()
        result = 31 * result + isServiceMessage.hashCode()
        result = 31 * result + (attachmentData?.contentHashCode() ?: 0)
        result = 31 * result + (attachmentType?.hashCode() ?: 0)
        return result
    }
}

@Composable
fun MessagingScreen(
    messages: List<Message>,
    onSend: (String) -> Unit,
    debugLogs: List<String>,
    discoveryStatus: String,
    lastReceivedMessage: String,
    lastSentMessage: String,
    onRefresh: () -> Unit,
    onSendAttachment: (ByteArray, String) -> Unit = { _, _ -> }
) {
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Setup image picker
    val launchImagePicker = rememberImagePickerLauncher { data, type ->
        onSendAttachment(data, type)
    }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // Header with status
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "WiFi Aware Chat",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Status: $discoveryStatus",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = { onRefresh() },
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Refresh", fontSize = 12.sp)
                    }
                }
            }
        }
        
        // Messages list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { message ->
                MessageBubble(message = message)
            }
        }
        
        // Debug logs (collapsible)
        var showDebugLogs by remember { mutableStateOf(false) }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Debug Logs",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    TextButton(onClick = { showDebugLogs = !showDebugLogs }) {
                        Text(if (showDebugLogs) "Hide" else "Show")
                    }
                }
                
                if (showDebugLogs) {
                    LazyColumn(
                        modifier = Modifier.height(120.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(debugLogs.takeLast(20)) { log ->
                            Text(
                                text = log,
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                    }
                }
            }
        }
        
        // Message input
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                // Attachment button
                IconButton(
                    onClick = { launchImagePicker() }
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Attach image",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message...") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (text.isNotBlank()) {
                            onSend(text)
                            text = ""
                        }
                    },
                    enabled = text.isNotBlank()
                ) {
                    Text("Send")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: Message) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = when {
            message.isServiceMessage -> Arrangement.Center
            message.isSent -> Arrangement.End
            else -> Arrangement.Start
        }
    ) {
        Card(
            modifier = Modifier.widthIn(max = 280.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    message.isServiceMessage -> MaterialTheme.colorScheme.secondaryContainer
                    message.isSent -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Display attachment if present
                if (message.isAttachment && message.attachmentData != null) {
                    when {
                        message.attachmentType?.startsWith("image/") == true -> {
                            val bitmap = decodeByteArrayToImageBitmap(message.attachmentData)
                            Image(
                                bitmap = bitmap,
                                contentDescription = "Attached image",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Fit
                            )
                            if (message.content.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                        else -> {
                            Text(
                                text = "[File attachment: ${message.attachmentType}]",
                                style = MaterialTheme.typography.bodySmall,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                            )
                        }
                    }
                }

                // Display text content if present
                if (message.content.isNotBlank()) {
                    Text(
                        text = message.content,
                        color = when {
                            message.isServiceMessage -> MaterialTheme.colorScheme.onSecondaryContainer
                            message.isSent -> MaterialTheme.colorScheme.onPrimary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

// Expect declaration for platform-specific image decoding
@Composable
expect fun decodeByteArrayToImageBitmap(byteArray: ByteArray): ImageBitmap

// Expect declaration for platform-specific image picker
@Composable
expect fun rememberImagePickerLauncher(onImageSelected: (ByteArray, String) -> Unit): () -> Unit
