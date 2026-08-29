package org.maplibre.compose.map

import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher

private val processHostDispatcher: CoroutineDispatcher by lazy {
  Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "maplibre-compose-host").apply { isDaemon = true }
    }
    .asCoroutineDispatcher()
}

internal actual fun defaultHostDispatcher(): CoroutineDispatcher =
  resolveHostDispatcher(main = { Dispatchers.Main.immediate }, fallback = { processHostDispatcher })
