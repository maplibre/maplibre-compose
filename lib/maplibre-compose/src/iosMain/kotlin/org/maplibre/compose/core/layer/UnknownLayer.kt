package org.maplibre.compose.core.layer

import cocoapods.MapLibre.MLNStyleLayer

internal actual class UnknownLayer(override val impl: MLNStyleLayer) : Layer()
