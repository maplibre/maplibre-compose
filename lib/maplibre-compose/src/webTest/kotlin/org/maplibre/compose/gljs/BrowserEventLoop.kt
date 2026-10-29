package org.maplibre.compose.gljs

import kotlin.js.JsAny
import kotlin.js.Promise
import kotlinx.coroutines.await

/** Lets real engine promises, timers, and fetches run outside the Compose test clock. */
internal suspend fun yieldToBrowser() {
  browserTurn().await()
}

private fun browserTurn(): Promise<JsAny?> =
  js("new Promise(resolve => setTimeout(() => resolve(null), 8))")
