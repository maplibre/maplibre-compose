package org.maplibre.compose.style.layer

import org.maplibre.android.style.layers.Layer as MLNLayer

internal actual class UnknownLayer(override val impl: MLNLayer) : Layer()
