package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.viewinterop.WebElementView
import co.touchlab.kermit.Logger
import kotlinx.browser.document
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.SafeStyle
import org.w3c.dom.HTMLElement

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
  WebMapView(
    modifier = modifier,
    style = style,
    update = update,
    onReset = onReset,
    logger = logger,
    callbacks = callbacks,
  )

@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun WebMapView(
  modifier: Modifier,
  style: BaseStyle,
  update: (map: MapAdapter) -> Unit,
  onReset: () -> Unit,
  logger: Logger?,
  callbacks: MapAdapter.Callbacks,
) {
  var maybeMap by remember { mutableStateOf<JsMapAdapter?>(null) }

  val layoutDir = LocalLayoutDirection.current
  val density = LocalDensity.current

  WebElementView(
    modifier = modifier.onGloballyPositioned { maybeMap?.resize() },
    factory = { document.createElement("div").unsafeCast<HTMLElement>() },
    update = { element ->
      val map =
        maybeMap
          ?: JsMapAdapter(element, layoutDir, density, callbacks, logger).also { maybeMap = it }
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
