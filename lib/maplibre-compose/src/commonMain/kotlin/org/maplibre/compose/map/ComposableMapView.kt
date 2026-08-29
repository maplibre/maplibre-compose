package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import co.touchlab.kermit.Logger
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleBinding

@Composable
internal expect fun ComposableMapView(
  modifier: Modifier,
  runtime: RuntimeImplementation?,
  style: BaseStyle,
  rememberedStyle: StyleBinding?,
  update: (map: MapAdapter) -> Unit,
  onReset: () -> Unit,
  logger: Logger?,
  callbacks: MapAdapter.Callbacks,
  options: MapOptions,
)
