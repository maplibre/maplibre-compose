package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.maplibre.compose.desktop.LocalComposeMapHost
import org.maplibre.compose.desktop.bridge.ComposeMapHostFactory

@Composable
internal actual fun ComposableMapView(state: MapState, modifier: Modifier, options: MapOptions) {
  val hostFactory =
    LocalMlnFfiMapHostFactory.current
      ?: LocalComposeMapHost.current.let { mapHost ->
        remember(mapHost) { ComposeMapHostFactory(mapHost) }
      }
  MlnFfiMapView(hostFactory = hostFactory, modifier = modifier, state = state, options = options)
}
