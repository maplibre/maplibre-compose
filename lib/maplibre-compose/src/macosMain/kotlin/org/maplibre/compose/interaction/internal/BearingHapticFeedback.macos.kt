package org.maplibre.compose.interaction.internal

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import platform.AppKit.NSHapticFeedbackManager
import platform.AppKit.NSHapticFeedbackPatternAlignment
import platform.AppKit.NSHapticFeedbackPerformanceTimeNow

internal actual val HapticFeedbackOverride: ((HapticFeedbackType) -> Unit)? = {
  NSHapticFeedbackManager.defaultPerformer.performFeedbackPattern(
    NSHapticFeedbackPatternAlignment,
    NSHapticFeedbackPerformanceTimeNow,
  )
}
