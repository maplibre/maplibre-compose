package org.maplibre.compose.map

import android.os.Build
import android.view.ViewConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberScrollConfig(): ScrollConfig {
  val context = LocalContext.current
  return remember(context) {
    val configuration = ViewConfiguration.get(context)
    ScrollConfig { event, density, _ ->
      // Match Compose's ViewConfiguration factors and its fallback for older Android releases.
      val horizontal =
        if (Build.VERSION.SDK_INT > 26) configuration.scaledHorizontalScrollFactor
        else 64f * density.density
      val vertical =
        if (Build.VERSION.SDK_INT > 26) configuration.scaledVerticalScrollFactor
        else 64f * density.density
      val raw = event.totalScrollDelta
      Offset(-raw.x * horizontal, -raw.y * vertical)
    }
  }
}
