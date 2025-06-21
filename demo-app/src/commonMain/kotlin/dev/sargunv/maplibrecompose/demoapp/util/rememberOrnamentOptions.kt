package dev.sargunv.maplibrecompose.demoapp.util

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import dev.sargunv.maplibrecompose.core.OrnamentOptions

@Composable expect fun rememberOrnamentOptions(padding: PaddingValues): OrnamentOptions
