package org.maplibre.compose.interaction.internal

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import org.maplibre.compose.desktop.bridge.ObjectiveC

internal actual val HapticFeedbackOverride: ((HapticFeedbackType) -> Unit)? =
  if (System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)) {
    { _ -> performMacosBearingHaptic() }
  } else {
    null
  }

internal fun performMacosBearingHaptic() {
  ObjectiveC.runInAutoreleasePool {
    // AppKit chooses the current device and honors preferences. All types use alignment.
    val performer = ObjectiveC.sendClassPointer("NSHapticFeedbackManager", "defaultPerformer")
    ObjectiveC.sendVoid(performer, "performFeedbackPattern:performanceTime:", 1L, 1L)
  }
}
