package org.maplibre.compose.style

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import co.touchlab.kermit.Logger
import dev.sargunv.composehtmlinterop.HtmlElement
import kotlinx.browser.document
import org.maplibre.compose.core.BaseStyle
import org.maplibre.compose.core.JsMap
import org.maplibre.compose.core.MapOptions
import org.maplibre.compose.core.MaplibreMap
import org.maplibre.compose.core.SafeStyle
import org.w3c.dom.HTMLElement

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
) =
  WebMapView(
    modifier = modifier,
    style = style,
    update = update,
    onReset = onReset,
    logger = logger,
    callbacks = callbacks,
  )

@Composable
internal fun WebMapView(
  modifier: Modifier,
  style: BaseStyle,
  update: (map: MaplibreMap) -> Unit,
  onReset: () -> Unit,
  logger: Logger?,
  callbacks: MaplibreMap.Callbacks,
) {
  var maybeMap by remember { mutableStateOf<JsMap?>(null) }

  val layoutDir = LocalLayoutDirection.current
  val density = LocalDensity.current

  HtmlElement(
    modifier = modifier.onGloballyPositioned { maybeMap?.resize() },
    // zIndex = "-1", // TODO figure out pointer interop
    factory = {
      document.createElement("div").unsafeCast<HTMLElement>().apply {
        this.style.apply {
          width = "100%"
          height = "100%"
        }
      }
    },
    update = { element ->
      val map =
        maybeMap ?: JsMap(element, layoutDir, density, callbacks, logger).also { maybeMap = it }
      map.setBaseStyle(style)
      map.layoutDir = layoutDir
      map.density = density
      map.callbacks = callbacks
      map.logger = logger
      update(map)
    },
  )

  DisposableEffect(Unit) { onDispose { onReset() } }
}
