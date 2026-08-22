package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import co.touchlab.kermit.Logger
import org.maplibre.compose.desktop.LocalComposeMapHost
import org.maplibre.compose.desktop.bridge.ComposeMapHostFactory
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.SafeStyle

@Composable
internal actual fun ComposableMapView(
  modifier: Modifier,
  style: BaseStyle,
  rememberedStyle: SafeStyle?,
  update: (map: MapAdapter) -> Unit,
  onReset: () -> Unit,
  logger: Logger?,
  callbacks: MapAdapter.Callbacks,
  options: MapOptions,
) {
  val hostFactory =
    LocalMlnFfiMapHostFactory.current
      ?: LocalComposeMapHost.current.let { mapHost ->
        remember(mapHost) { ComposeMapHostFactory(mapHost) }
      }
  MlnFfiMapView(
    hostFactory = hostFactory,
    modifier = modifier,
    style = style,
    rememberedStyle = rememberedStyle,
    update = update,
    onReset = onReset,
    logger = logger,
    callbacks = callbacks,
    options = options,
  )
}
