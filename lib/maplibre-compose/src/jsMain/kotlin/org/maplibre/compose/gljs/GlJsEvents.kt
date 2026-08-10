package org.maplibre.compose.gljs

/** Cancels an event subscription. */
internal fun interface GlJsSubscription {
  fun cancel()
}

internal fun MaplibreMap.subscribe(event: String, listener: (MapEvent) -> Unit): GlJsSubscription {
  val subscription = on(event, listener)
  return GlJsSubscription { subscription.unsubscribe() }
}
