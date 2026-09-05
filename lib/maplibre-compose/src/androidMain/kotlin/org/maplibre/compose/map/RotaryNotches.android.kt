package org.maplibre.compose.map

import android.os.Build
import android.view.ViewConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Compose scales rotary events by this same factor before they reach the map. */
@Composable
internal actual fun rotaryNotchPixels(): Float {
  val context = LocalContext.current
  return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    ViewConfiguration.get(context).scaledVerticalScrollFactor
  } else {
    // The framework default before the factor became readable.
    with(LocalDensity.current) { 64.dp.toPx() }
  }
}
