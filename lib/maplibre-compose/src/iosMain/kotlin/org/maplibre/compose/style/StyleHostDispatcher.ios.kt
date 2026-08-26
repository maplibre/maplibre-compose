package org.maplibre.compose.style

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
internal actual fun styleHostDispatcher(): StyleHostDispatcher =
  object : StyleHostDispatcher {
    private val context = newSingleThreadContext("maplibre-style")

    override val dispatcher: CoroutineDispatcher = context

    // close blocks on the worker's termination, so it must not run on the worker itself.
    override fun close() {
      GlobalScope.launch(Dispatchers.Default) { context.close() }
    }
  }
