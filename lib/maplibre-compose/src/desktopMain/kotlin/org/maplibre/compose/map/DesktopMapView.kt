package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import co.touchlab.kermit.Logger
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.SafeStyle
import org.maplibre.kmp.native.map.MapCanvas
import org.maplibre.kmp.native.map.MapControls

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
) =
  DesktopMapView(
    modifier = modifier,
    style = style,
    rememberedStyle = rememberedStyle,
    update = update,
    onReset = onReset,
    logger = logger,
    callbacks = callbacks,
    options = options,
  )

@Composable
internal fun DesktopMapView(
  modifier: Modifier,
  style: BaseStyle,
  rememberedStyle: SafeStyle?,
  update: (map: MapAdapter) -> Unit,
  onReset: () -> Unit,
  logger: Logger?,
  callbacks: MapAdapter.Callbacks,
  options: MapOptions,
) {
  val currentOnReset by rememberUpdatedState(onReset)
  var currentMapAdapter by remember { mutableStateOf<DesktopMapAdapter?>(null) }

  SwingPanel(
    background = Color.Transparent,
    factory = {
      val adapter = DesktopMapAdapter(callbacks)
      MapCanvas(
        mapObserver = adapter,
        onMapReady = { map, canvas ->
          val controls =
            MapControls(
              component = canvas,
              map = map,
              config = options.gestureOptions.toMapControlsConfig(),
              observer = adapter,
            )
          controls.enable()
          adapter.map = map
          adapter.mapControls = controls
          adapter.setBaseStyle(style)
          currentMapAdapter = adapter
        },
      )
    },
    update = { _ ->
      currentMapAdapter?.let { adapter ->
        adapter.callbacks = callbacks
        adapter.setBaseStyle(style)
        update(adapter)
      }
    },
    modifier = modifier,
  )
  DisposableEffect(Unit) { onDispose { currentOnReset() } }
}
