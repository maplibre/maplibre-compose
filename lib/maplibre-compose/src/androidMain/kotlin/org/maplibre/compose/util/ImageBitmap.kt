package org.maplibre.compose.util

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

internal actual val imageBitmapContext: CoroutineContext = Dispatchers.Default

internal actual fun IntArray.toImageBitmap(width: Int, height: Int): ImageBitmap {
  return Bitmap.createBitmap(this, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
