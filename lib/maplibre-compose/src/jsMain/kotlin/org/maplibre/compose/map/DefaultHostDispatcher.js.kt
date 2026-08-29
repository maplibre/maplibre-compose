package org.maplibre.compose.map

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual fun defaultHostDispatcher(): CoroutineDispatcher = Dispatchers.Main
