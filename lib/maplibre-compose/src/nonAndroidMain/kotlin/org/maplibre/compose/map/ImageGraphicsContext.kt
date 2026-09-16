package org.maplibre.compose.map

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.SkiaGraphicsContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val imageDispatcher = Dispatchers.Default.limitedParallelism(1)

@OptIn(InternalComposeUiApi::class)
internal actual suspend fun <T> withImageGraphicsContext(block: suspend (GraphicsContext) -> T): T =
  // Keep composition effects and image capture serialized even when the caller uses a thread pool.
  withContext(imageDispatcher) {
    val context = SkiaGraphicsContext()
    try {
      block(context)
    } finally {
      context.dispose()
    }
  }
