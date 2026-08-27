package org.maplibre.compose.util

import androidx.compose.ui.graphics.ImageBitmap

/** Wraps ARGB color ints with straight alpha, the encoding that Compose's `readPixels` emits. */
internal expect fun IntArray.toImageBitmap(width: Int, height: Int): ImageBitmap
