package org.maplibre.compose.map

import android.widget.FrameLayout
import androidx.compose.ui.graphics.GraphicsContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.compose.mlnffi.AndroidMlnFfiPlatform

internal actual suspend fun <T> withSnapshotGraphicsContext(
  block: suspend (GraphicsContext) -> T
): T =
  withContext(Dispatchers.Main.immediate) {
    val container = FrameLayout(AndroidMlnFfiPlatform.applicationContext)
    block(GraphicsContext(container))
  }
