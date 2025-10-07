package com.jetbrains.kmpapp.screens.messaging

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.io.InputStream

@Composable
actual fun rememberImagePickerLauncher(onImageSelected: (ByteArray, String) -> Unit): () -> Unit {
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                val bytes = inputStream?.readBytes()
                val mimeType = context.contentResolver.getType(uri) ?: "image/*"

                if (bytes != null) {
                    onImageSelected(bytes, mimeType)
                }
                inputStream?.close()
            } catch (e: Exception) {
                android.util.Log.e("ImagePicker", "Failed to read image: ${e.message}")
            }
        }
    }

    return { launcher.launch("image/*") }
}
