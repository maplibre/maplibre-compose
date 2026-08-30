package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import co.touchlab.kermit.Logger
import org.maplibre.compose.desktop.LocalComposeMapPresentationHost
import org.maplibre.compose.desktop.bridge.ComposeMapPresentationHostFactory
import org.maplibre.compose.style.BaseStyle

@Composable
internal actual fun mapPresentationHostIdentity(): Any =
  LocalMlnFfiMapHostFactory.current ?: LocalComposeMapPresentationHost.current

@Composable
internal actual fun ComposableMapView(
  modifier: Modifier,
  state: MapState,
  style: BaseStyle,
  update: (map: MapAdapter) -> Unit,
  onReset: () -> Unit,
  logger: Logger?,
  callbacks: MapAdapter.Callbacks,
  options: MapPresentationOptions,
) {
  val hostFactory =
    LocalMlnFfiMapHostFactory.current
      ?: LocalComposeMapPresentationHost.current.let { presentationHost ->
        remember(presentationHost) { ComposeMapPresentationHostFactory(presentationHost) }
      }
  MlnFfiMapView(
    hostFactory = hostFactory,
    modifier = modifier,
    state = state,
    style = style,
    update = update,
    onReset = onReset,
    logger = logger,
    callbacks = callbacks,
    options = options,
  )
}
