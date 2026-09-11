package org.maplibre.compose.map

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.SkiaGraphicsContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(InternalComposeUiApi::class)
internal actual suspend fun <T> withSnapshotGraphicsContext(
  block: suspend (GraphicsContext) -> T
): T =
  // Keep composition effects and image capture serialized even when the caller uses a thread pool.
  withContext(Dispatchers.Default.limitedParallelism(1)) {
    val context = SkiaGraphicsContext()
    try {
      block(context)
    } finally {
      context.dispose()
    }
  }
