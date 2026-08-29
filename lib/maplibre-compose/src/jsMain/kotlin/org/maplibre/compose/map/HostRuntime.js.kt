package org.maplibre.compose.map

import kotlinx.coroutines.CoroutineDispatcher

internal actual fun <T> runBlockingOn(dispatcher: CoroutineDispatcher, block: () -> T): T = block()
