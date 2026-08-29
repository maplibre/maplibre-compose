package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import co.touchlab.kermit.Logger
import org.maplibre.compose.desktop.LocalComposeMapHost
import org.maplibre.compose.desktop.bridge.ComposeMapHostFactory
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleBinding

@Composable
internal actual fun ComposableMapView(
  modifier: Modifier,
  runtime: RuntimeImplementation?,
  style: BaseStyle,
  rememberedStyle: StyleBinding?,
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
    runtimeOptions = runtime?.nativeRuntimeOptions,
    style = style,
    update = update,
    onReset = onReset,
    logger = logger,
    callbacks = callbacks,
    options = options,
  )
}
