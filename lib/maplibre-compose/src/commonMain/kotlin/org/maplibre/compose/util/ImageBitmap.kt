package org.maplibre.compose.util

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.coroutines.CoroutineContext

internal expect fun IntArray.toImageBitmap(width: Int, height: Int): ImageBitmap

/** Android processes bitmap pixels on workers; Skia keeps the caller's graphics dispatcher. */
internal expect val imageBitmapContext: CoroutineContext
