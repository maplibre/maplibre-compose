package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Identifies the platform presentation host that owns the current UI surface. */
@Composable internal expect fun mapPresentationHostIdentity(): Any

@Composable
internal expect fun ComposableMapView(
  modifier: Modifier,
  state: MapState,
  presentationOwner: MapPresentationOwnerToken,
  options: MapViewOptions,
)
