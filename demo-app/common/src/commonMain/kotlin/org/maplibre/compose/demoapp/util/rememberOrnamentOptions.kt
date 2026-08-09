package org.maplibre.compose.demoapp.util

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import org.maplibre.compose.map.OrnamentOptions

@Composable expect fun rememberOrnamentOptions(padding: PaddingValues): OrnamentOptions
