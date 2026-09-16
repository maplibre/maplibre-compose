package org.maplibre.compose.map

import androidx.compose.ui.graphics.GraphicsContext

internal expect suspend fun <T> withImageGraphicsContext(block: suspend (GraphicsContext) -> T): T
