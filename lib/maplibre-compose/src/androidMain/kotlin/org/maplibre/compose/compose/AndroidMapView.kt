package org.maplibre.compose.compose

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import co.touchlab.kermit.Logger
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.compose.core.*

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
