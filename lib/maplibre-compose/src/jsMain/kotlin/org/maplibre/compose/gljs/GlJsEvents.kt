package org.maplibre.compose.gljs

internal fun interface GlJsSubscription {
  fun cancel()
}

internal fun MaplibreMap.subscribe(event: String, listener: (MapEvent) -> Unit): GlJsSubscription {
  val subscription = on(event, listener)
  return GlJsSubscription { subscription.unsubscribe() }
}

/** Whether an error event ended a base-style request rather than one source or tile request. */
internal fun MapEvent.isTerminalStyleLoadFailure(): Boolean {
  val style = asDynamic().style ?: return false
  return style._loaded != true && style._loadStyleRequest == null && style._frameRequest == null
}
