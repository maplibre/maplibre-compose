package org.maplibre.compose.style

import MapLibre.MLNMapView
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.onConsumedWindowInsetsChanged
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import co.touchlab.kermit.Logger
import org.maplibre.compose.core.BaseStyle
import org.maplibre.compose.core.IosMap
import org.maplibre.compose.core.MapOptions
import org.maplibre.compose.core.MaplibreMap
import org.maplibre.compose.core.SafeStyle
import org.maplibre.compose.core.util.afterConsuming
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSURL

@Composable
internal actual fun ComposableMapView(
  modifier: Modifier,
  style: BaseStyle,
  rememberedStyle: SafeStyle?,
  update: (map: MaplibreMap) -> Unit,
  onReset: () -> Unit,
  logger: Logger?,
  callbacks: MaplibreMap.Callbacks,
  options: MapOptions,
) {
  IosMapView(
    modifier = modifier,
    style = style,
    update = update,
    onReset = onReset,
    logger = logger,
    callbacks = callbacks,
  )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun IosMapView(
  modifier: Modifier,
  style: BaseStyle,
  update: (map: MaplibreMap) -> Unit,
  onReset: () -> Unit,
  logger: Logger?,
  callbacks: MaplibreMap.Callbacks,
) {
  var consumedInsets by remember { mutableStateOf(WindowInsets(0, 0, 0, 0)) }
  val insetPadding = WindowInsets.safeDrawing.afterConsuming(consumedInsets).asPaddingValues()
  MeasuredBox(
    modifier = modifier.fillMaxSize().onConsumedWindowInsetsChanged { consumedInsets = it }
  ) { x, y, width, height ->
    val layoutDir = LocalLayoutDirection.current
    val density = LocalDensity.current
    val currentOnReset by rememberUpdatedState(onReset)
    var currentMap by remember { mutableStateOf<IosMap?>(null) }

    UIKitView(
      modifier = modifier.fillMaxSize(),
      properties =
        UIKitInteropProperties(interactionMode = UIKitInteropInteractionMode.NonCooperative),
      factory = {
        val frame =
          CGRectMake(
            x = x.value.toDouble(),
            y = y.value.toDouble(),
            width = width.value.toDouble(),
            height = height.value.toDouble(),
          )
        val mapView =
          when (style) {
            is BaseStyle.Uri -> MLNMapView(frame = frame, styleURL = NSURL(string = style.uri))
            is BaseStyle.Json -> MLNMapView(frame = frame, styleJSON = style.json)
          }
        currentMap =
          IosMap(
            mapView = mapView,
            size = CGSizeMake(width.value.toDouble(), height.value.toDouble()),
            layoutDir = layoutDir,
            density = density,
            insetPadding = insetPadding,
            callbacks = callbacks,
            logger = logger,
          )
        mapView
      },
      update = { _ ->
        val map = currentMap ?: return@UIKitView
        map.size = CGSizeMake(width.value.toDouble(), height.value.toDouble())
        map.layoutDir = layoutDir
        map.density = density
        map.insetPadding = insetPadding
        map.callbacks = callbacks
        map.logger = logger
        map.setBaseStyle(style)
        update(map)
      },
      onReset = {
        currentOnReset()
        currentMap = null
      },
    )
  }
}
