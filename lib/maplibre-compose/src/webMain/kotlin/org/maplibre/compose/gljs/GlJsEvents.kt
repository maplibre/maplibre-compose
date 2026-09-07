package org.maplibre.compose.gljs

internal fun interface GlJsSubscription {
  fun cancel()
}

internal fun MaplibreMap.subscribe(
  event: String,
  listener: (GlJsMapEvent) -> Unit,
): GlJsSubscription {
  val subscription = on(event, listener)
  return GlJsSubscription { subscription.unsubscribe() }
}

internal fun Light.subscribe(event: String, listener: (GlJsMapEvent) -> Unit): GlJsSubscription {
  val subscription = on(event, listener)
  return GlJsSubscription { subscription.unsubscribe() }
}

internal fun Sky.subscribe(event: String, listener: (GlJsMapEvent) -> Unit): GlJsSubscription {
  val subscription = on(event, listener)
  return GlJsSubscription { subscription.unsubscribe() }
}

/** The `z/x/y` of the tile whose load an error event reports, or null when it reports none. */
internal fun GlJsMapEvent.failedTile(): String? {
  val tile = asDynamic().tile?.tileID?.canonical ?: return null
  return "${tile.z}/${tile.x}/${tile.y}"
}

/** The URL of the request that an error event reports failed, or null when it names none. */
internal fun GlJsMapEvent.failedUrl(): String? = error?.asDynamic()?.url as? String

/** Whether an error event ended a base-style request rather than one source or tile request. */
internal fun GlJsMapEvent.isTerminalStyleLoadFailure(): Boolean = terminalStyleLoadFailure(this)

private fun terminalStyleLoadFailure(event: GlJsMapEvent): Boolean =
  js(
    "!!event.style && event.style._loaded !== true && event.style._loadStyleRequest == null && event.style._frameRequest == null"
  )
