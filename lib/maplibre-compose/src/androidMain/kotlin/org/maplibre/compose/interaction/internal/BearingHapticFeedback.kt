package org.maplibre.compose.interaction.internal

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import org.maplibre.compose.interaction.HapticEmphasis

@Composable
internal actual fun rememberBearingHapticFeedback(): (HapticEmphasis) -> Unit {
  val view = LocalView.current
  return remember(view) {
    { emphasis ->
      val constant =
        when (emphasis) {
          HapticEmphasis.Subtle ->
            if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
            else HapticFeedbackConstants.CLOCK_TICK
          HapticEmphasis.Standard ->
            if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK
            else HapticFeedbackConstants.CLOCK_TICK
          HapticEmphasis.Emphasized -> HapticFeedbackConstants.CONTEXT_CLICK
        }
      view.performHapticFeedback(constant)
    }
  }
}
