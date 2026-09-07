package org.maplibre.compose.interaction.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.maplibre.compose.interaction.HapticEmphasis
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle

@Composable
internal actual fun rememberBearingHapticFeedback(): (HapticEmphasis) -> Unit = remember {
  val generators =
    HapticEmphasis.entries.associateWith { emphasis ->
      UIImpactFeedbackGenerator(
        style =
          when (emphasis) {
            HapticEmphasis.Subtle -> UIImpactFeedbackStyle.UIImpactFeedbackStyleLight
            HapticEmphasis.Standard -> UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium
            HapticEmphasis.Emphasized -> UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy
          }
      )
    }
  return@remember { emphasis: HapticEmphasis -> generators.getValue(emphasis).impactOccurred() }
}
