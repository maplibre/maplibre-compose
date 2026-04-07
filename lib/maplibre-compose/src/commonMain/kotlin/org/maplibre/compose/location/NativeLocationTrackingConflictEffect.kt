package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import co.touchlab.kermit.Logger

@Composable
internal fun NativeLocationTrackingConflictEffect(
  nativeTrackingEnabled: Boolean,
  hasComposeLocationPuck: Boolean,
  logger: Logger?,
) {
  val hasLoggedConflict = remember { mutableStateOf(false) }

  LaunchedEffect(nativeTrackingEnabled, hasComposeLocationPuck, logger) {
    val shouldWarn = nativeTrackingEnabled && hasComposeLocationPuck
    if (shouldWarn && !hasLoggedConflict.value) {
      logger?.w {
        "NativeLocationTracking and Compose LocationPuck are active in the same MaplibreMap. Use only one puck pipeline per location source."
      }
      hasLoggedConflict.value = true
    } else if (!shouldWarn) {
      hasLoggedConflict.value = false
    }
  }
}
