package com.jetbrains.kmpapp.screens.messaging

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory

@Composable
actual fun decodeByteArrayToImageBitmap(byteArray: ByteArray): ImageBitmap {
    return remember(byteArray) {
        val bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
        bitmap.asImageBitmap()
    }
}
