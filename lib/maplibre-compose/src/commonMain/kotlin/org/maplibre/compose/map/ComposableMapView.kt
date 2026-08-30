package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import co.touchlab.kermit.Logger
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleBinding

/** Identifies the platform presentation host that owns the current UI surface. */
@Composable internal expect fun mapPresentationHostIdentity(): Any

@Composable
internal expect fun ComposableMapView(
  modifier: Modifier,
  runtime: RuntimeImplementation?,
  state: MapState?,
  style: BaseStyle,
  rememberedStyle: StyleBinding?,
  update: (map: MapAdapter) -> Unit,
  onReset: () -> Unit,
  logger: Logger?,
  callbacks: MapAdapter.Callbacks,
  options: MapPresentationOptions,
)
