package org.maplibre.compose.map

import android.os.Build
import android.widget.FrameLayout
import androidx.compose.ui.graphics.GraphicsContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.compose.mlnffi.AndroidMlnFfiPlatform

private val imageDispatcher = Dispatchers.Default.limitedParallelism(1)

internal actual suspend fun <T> withImageGraphicsContext(block: suspend (GraphicsContext) -> T): T =
  withContext(Dispatchers.Main.immediate) {
    val context = GraphicsContext(FrameLayout(AndroidMlnFfiPlatform.applicationContext))
    // API 29+ uses public RenderNodes and captures synchronously, so each layer is recorded,
    // captured, and released on one worker. Older versions can fall back to View-backed layers.
    if (Build.VERSION.SDK_INT >= 29) withContext(imageDispatcher) { block(context) }
    else block(context)
  }
