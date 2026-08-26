package org.maplibre.compose.style

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// The browser has one thread, so the host shares it and there is nothing to release.
internal actual fun styleHostDispatcher(): StyleHostDispatcher =
  object : StyleHostDispatcher {
    override val dispatcher: CoroutineDispatcher = Dispatchers.Default

    override fun close() = Unit
  }
