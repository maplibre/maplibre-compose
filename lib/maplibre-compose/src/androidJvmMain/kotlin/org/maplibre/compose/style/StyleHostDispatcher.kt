package org.maplibre.compose.style

import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

internal actual fun styleHostDispatcher(): StyleHostDispatcher =
  object : StyleHostDispatcher {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "maplibre-style").apply { isDaemon = true }
    }

    override val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    // shutdown is non-blocking, so calling it from the executor's own thread is safe.
    override fun close() = executor.shutdown()
  }
