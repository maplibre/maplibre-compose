package org.maplibre.compose.interaction.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/** Called on the Compose input dispatcher. */
@Composable
internal fun rememberBearingHapticFeedback(): (HapticFeedbackType) -> Unit {
  val feedback = LocalHapticFeedback.current
  return remember(feedback) { HapticFeedbackOverride ?: feedback::performHapticFeedback }
}

/** Replaces [LocalHapticFeedback] where Compose provides no haptics; null elsewhere. */
internal expect val HapticFeedbackOverride: ((HapticFeedbackType) -> Unit)?
