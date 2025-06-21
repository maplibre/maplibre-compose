package dev.sargunv.maplibrecompose.demoapp.util

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.sargunv.maplibrecompose.core.OrnamentOptions

@Composable
actual fun rememberOrnamentOptions(padding: PaddingValues): OrnamentOptions {
  return remember(padding) { OrnamentOptions.OnlyLogo.copy(padding = padding) }
}
