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

/** Whether an error event ended a base-style request rather than one source or tile request. */
internal fun GlJsMapEvent.isTerminalStyleLoadFailure(): Boolean = terminalStyleLoadFailure(this)

private fun terminalStyleLoadFailure(event: GlJsMapEvent): Boolean =
  js(
    "!!event.style && event.style._loaded !== true && event.style._loadStyleRequest == null && event.style._frameRequest == null"
  )
