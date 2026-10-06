package org.maplibre.compose.map

import js.objects.unsafeJso
import org.maplibre.compose.gljs.GlJsSubscription
import org.maplibre.compose.gljs.MapOptions
import org.maplibre.compose.gljs.MaplibreMap
import org.maplibre.compose.gljs.SetStyleOptions
import org.maplibre.compose.gljs.isTerminalStyleLoadFailure
import org.maplibre.compose.gljs.styleJson
import org.maplibre.compose.gljs.styleUrl
import org.maplibre.compose.gljs.subscribe
import org.maplibre.compose.resource.GlJsRequestController
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.UnspecifiedBaseStyle
import web.html.HTMLElement

/**
 * Options for a map that takes no input and shows no controls of its own, with [configure] adding
 * what one caller needs.
 */
internal fun headlessMapOptions(
  container: HTMLElement,
  pixelRatio: Double,
  requests: GlJsRequestController?,
  configure: MapOptions.() -> Unit = {},
): MapOptions = unsafeJso {
  this.container = container
  interactive = false
  attributionControl = false
  maplibreLogo = false
  this.pixelRatio = pixelRatio
  requests?.let { controller ->
    transformRequest = { url, resourceType -> controller.transformRequest(url, resourceType) }
  }
  configure()
}

/**
 * Replaces this map's style with [style] and reports the outcome once: [onLoaded] on `style.load`,
 * or [onFailed] with MapLibre's message on an `error` event that ends the request. Cancelling the
 * returned subscription drops an outcome that has not arrived.
 *
 * An inline style is parsed here rather than fetched, so a malformed one throws from this call
 * where every other load failure reaches [onFailed].
 */
internal fun MaplibreMap.loadBaseStyle(
  style: BaseStyle,
  onLoaded: () -> Unit,
  onFailed: (message: String?) -> Unit,
): GlJsSubscription {
  lateinit var loadListener: GlJsSubscription
  lateinit var errorListener: GlJsSubscription
  val subscription = GlJsSubscription {
    loadListener.cancel()
    errorListener.cancel()
  }
  loadListener =
    subscribe("style.load") {
      subscription.cancel()
      onLoaded()
    }
  errorListener =
    subscribe("error") { event ->
      if (!event.isTerminalStyleLoadFailure()) return@subscribe
      subscription.cancel()
      onFailed(event.error?.message)
    }
  // MapLibre diffs by default, keeping the same Style object, so no `style.load` would fire.
  val options = unsafeJso<SetStyleOptions> { diff = false }
  try {
    when (style) {
      is BaseStyle.Uri -> setStyle(styleUrl(style.uri), options)
      is BaseStyle.Json -> setStyle(styleJson(style.json), options)
      UnspecifiedBaseStyle -> error("UnspecifiedBaseStyle is never created")
    }
  } catch (error: Throwable) {
    subscription.cancel()
    throw error
  }
  return subscription
}
