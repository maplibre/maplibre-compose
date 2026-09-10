package org.maplibre.compose.interaction.internal

import androidx.compose.runtime.Composable
import org.maplibre.compose.interaction.HapticEmphasis
import platform.AppKit.NSHapticFeedbackManager
import platform.AppKit.NSHapticFeedbackPatternAlignment
import platform.AppKit.NSHapticFeedbackPerformanceTimeNow

@Composable
internal actual fun rememberBearingHapticFeedback(): (HapticEmphasis) -> Unit = {
  NSHapticFeedbackManager.defaultPerformer.performFeedbackPattern(
    NSHapticFeedbackPatternAlignment,
    NSHapticFeedbackPerformanceTimeNow,
  )
}
