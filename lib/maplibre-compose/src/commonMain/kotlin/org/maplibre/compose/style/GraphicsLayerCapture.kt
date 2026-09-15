package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

internal expect suspend fun GraphicsLayer.captureImage(
  density: Density,
  layoutDirection: LayoutDirection,
): ImageBitmap
