package org.maplibre.compose.layers

import androidx.compose.runtime.staticCompositionLocalOf

// Multiple rendering layers can represent one logical click target.
internal val LocalLayerClickGroup = staticCompositionLocalOf<Any?> { null }
