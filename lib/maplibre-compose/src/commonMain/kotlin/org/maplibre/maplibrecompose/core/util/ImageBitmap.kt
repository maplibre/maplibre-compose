package org.maplibre.maplibrecompose.core.util

import androidx.compose.ui.graphics.ImageBitmap

internal expect fun IntArray.toImageBitmap(width: Int, height: Int): ImageBitmap
