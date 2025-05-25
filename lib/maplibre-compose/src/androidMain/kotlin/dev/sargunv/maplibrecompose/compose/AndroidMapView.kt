package dev.sargunv.maplibrecompose.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import co.touchlab.kermit.Logger
import dev.sargunv.maplibrecompose.core.AndroidMap
import dev.sargunv.maplibrecompose.core.AndroidMapSnapshotter
import dev.sargunv.maplibrecompose.core.AndroidScaleBar
import dev.sargunv.maplibrecompose.core.MapOptions
import dev.sargunv.maplibrecompose.core.MaplibreMap
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
  platformOptions: MapOptions,
) {
  AndroidMapView(
    modifier = modifier,
    styleUri = styleUri,
    rememberedStyle = rememberedStyle,
    update = update,
    onReset = onReset,
    logger = logger,
    callbacks = callbacks,
    platformOptions = platformOptions,
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
  platformOptions: MapOptions,
) {
  val layoutDir = LocalLayoutDirection.current
  val density = LocalDensity.current
  val currentOnReset by rememberUpdatedState(onReset)

  var currentMapView by remember { mutableStateOf<MapView?>(null) }
  var currentMap by remember { mutableStateOf<AndroidMap?>(null) }

  MapViewLifecycleEffect(currentMapView, rememberedStyle)

  AndroidView(
    modifier = modifier,
    factory = { context ->
      MapLibre.getInstance(context)
      MapView(
          context,
          MapLibreMapOptions.createFromAttributes(context).textureMode(platformOptions.textureMode),
        )
        .also { mapView ->
          currentMapView = mapView
          mapView.getMapAsync { map ->
            currentMap =
              AndroidMap(
                mapView = mapView,
                map = map,
                scaleBar = AndroidScaleBar(context, mapView, map),
                mapSnapshotter = AndroidMapSnapshotter(context, layoutDir, density),
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
      map.getMapSnapshotter().density = density
      map.getMapSnapshotter().layoutDir = layoutDir
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
