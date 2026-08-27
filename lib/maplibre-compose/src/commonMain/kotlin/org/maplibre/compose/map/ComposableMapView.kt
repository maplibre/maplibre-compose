package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The render session on [state]: it creates the platform map, attaches it through
 * [MapState.attachSession], and detaches it when the composable leaves.
 */
@Composable
internal expect fun ComposableMapView(state: MapState, modifier: Modifier, options: MapOptions)
