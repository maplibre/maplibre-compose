package org.maplibre.compose.layers

import org.maplibre.kmp.native.style.layers.Layer as MLNLayer

internal actual class UnknownLayer(override val impl: MLNLayer) : Layer()
