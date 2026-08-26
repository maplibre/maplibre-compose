package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import co.touchlab.kermit.Logger
import org.maplibre.compose.style.BaseStyle

@Composable
internal expect fun ComposableMapView(
  modifier: Modifier,
  style: BaseStyle,
  update: (map: MapAdapter) -> Unit,
  onReset: () -> Unit,
  logger: Logger?,
  callbacks: MapAdapter.Callbacks,
  options: MapOptions,
)
