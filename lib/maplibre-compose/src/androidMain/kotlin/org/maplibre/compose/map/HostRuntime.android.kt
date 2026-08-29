package org.maplibre.compose.map

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

internal actual fun <T> runBlockingOn(dispatcher: CoroutineDispatcher, block: () -> T): T =
  runBlocking {
    withContext(dispatcher) { block() }
  }
