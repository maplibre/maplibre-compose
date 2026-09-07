package org.maplibre.compose.interaction.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.maplibre.compose.desktop.bridge.ObjectiveC
import org.maplibre.compose.interaction.HapticEmphasis

@Composable
internal actual fun rememberBearingHapticFeedback(): (HapticEmphasis) -> Unit = remember {
  if (System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)) {
    { _: HapticEmphasis -> performMacosBearingHaptic() }
  } else {
    { _: HapticEmphasis -> }
  }
}

internal fun performMacosBearingHaptic() {
  ObjectiveC.runInAutoreleasePool {
    // AppKit chooses the current device and honors preferences. All emphasis levels use alignment.
    val performer = ObjectiveC.sendClassPointer("NSHapticFeedbackManager", "defaultPerformer")
    ObjectiveC.sendVoid(performer, "performFeedbackPattern:performanceTime:", 1L, 1L)
  }
}
