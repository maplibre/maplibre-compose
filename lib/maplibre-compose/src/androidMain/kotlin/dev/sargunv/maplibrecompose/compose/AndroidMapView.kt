package dev.sargunv.maplibrecompose.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import co.touchlab.kermit.Logger
import dev.sargunv.maplibrecompose.core.AndroidMap
import dev.sargunv.maplibrecompose.core.AndroidScaleBar
import dev.sargunv.maplibrecompose.core.MapOptions
import dev.sargunv.maplibrecompose.core.MaplibreMap
import dev.sargunv.maplibrecompose.core.RenderOptions
import dev.sargunv.maplibrecompose.core.SafeStyle
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView

@Composable
internal actual fun ComposableMapView(
  modifier: Modifier,
  styleUri: String,
  rememberedStyle: SafeStyle?,
  update: (map: MaplibreMap) -> Unit,
  onReset: () -> Unit,
  logger: Logger?,
  callbacks: MaplibreMap.Callbacks,
  options: MapOptions,
) {
  AndroidMapView(
    modifier = modifier,
    styleUri = styleUri,
    rememberedStyle = rememberedStyle,
    update = update,
    onReset = onReset,
    logger = logger,
    callbacks = callbacks,
    options = options,
  )
}

@Composable
internal fun AndroidMapView(
  modifier: Modifier,
  styleUri: String,
  rememberedStyle: SafeStyle?,
  update: (map: MaplibreMap) -> Unit,
  onReset: () -> Unit,
  logger: Logger?,
  callbacks: MaplibreMap.Callbacks,
  options: MapOptions,
) {
  val layoutDir = LocalLayoutDirection.current
  val density = LocalDensity.current
  val currentOnReset by rememberUpdatedState(onReset)

  var currentMapView by remember { mutableStateOf<MapView?>(null) }
  var currentMap by remember { mutableStateOf<AndroidMap?>(null) }

  MapViewLifecycleEffect(currentMapView, rememberedStyle)

  // load these ahead of time so the factory doesn't capture the entire options object
  val foregroundLoadColor = options.renderOptions.foregroundLoadColor
  val renderMode = options.renderOptions.renderMode

  AndroidView(
    modifier = modifier,
    factory = { context ->
      MapLibre.getInstance(context)
      MapView(
          context,
          MapLibreMapOptions.createFromAttributes(context)
            .foregroundLoadColor(foregroundLoadColor.toArgb())
            .textureMode(renderMode == RenderOptions.RenderMode.TextureView),
        )
        .also { mapView ->
          currentMapView = mapView
          mapView.getMapAsync { map ->
            currentMap =
              AndroidMap(
                mapView = mapView,
                map = map,
                scaleBar = AndroidScaleBar(context, mapView, map),
                layoutDir = layoutDir,
                density = density,
                callbacks = callbacks,
                styleUri = styleUri,
                logger = logger,
              )

            currentMap?.let { update(it) }
          }
        }
    },
    update = { _ ->
      val map = currentMap ?: return@AndroidView
      map.layoutDir = layoutDir
      map.density = density
      map.callbacks = callbacks
      map.logger = logger
      map.setStyleUri(styleUri)
      update(map)
    },
    onReset = {
      currentOnReset()
      currentMap = null
      currentMapView = null
    },
  )
}
