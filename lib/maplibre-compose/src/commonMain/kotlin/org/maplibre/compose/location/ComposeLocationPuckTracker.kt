package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf

internal class ComposeLocationPuckTracker {
  private val countState = mutableStateOf(0)

  val isPresent: Boolean
    get() = countState.value > 0

  fun register() {
    countState.value += 1
  }

  fun unregister() {
    countState.value = (countState.value - 1).coerceAtLeast(0)
  }
}

internal val LocalComposeLocationPuckTracker =
  compositionLocalOf<ComposeLocationPuckTracker?> { null }

@Composable
internal fun TrackComposeLocationPuck() {
  val tracker = LocalComposeLocationPuckTracker.current
  DisposableEffect(tracker) {
    tracker?.register()
    onDispose { tracker?.unregister() }
  }
}
