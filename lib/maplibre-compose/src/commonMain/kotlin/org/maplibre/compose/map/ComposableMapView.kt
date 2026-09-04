package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.style.BaseStyle

/** Identifies the platform presentation host that owns the current UI surface. */
@Composable internal expect fun mapPresentationHostIdentity(): Any

@Composable
internal expect fun ComposableMapView(
  modifier: Modifier,
  state: MapState,
  style: BaseStyle,
  update: (map: MapAdapter) -> Unit,
  onReset: () -> Unit,
  logger: MapLog?,
  callbacks: MapAdapter.Callbacks,
  clicks: MapClickTarget,
  options: MapViewOptions,
)
