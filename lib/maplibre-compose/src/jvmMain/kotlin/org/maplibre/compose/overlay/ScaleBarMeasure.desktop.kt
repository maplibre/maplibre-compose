package org.maplibre.compose.overlay

import androidx.compose.runtime.Composable
import org.maplibre.compose.desktop.bridge.ObjectiveC

@Composable
internal actual fun systemDefaultPrimaryMeasure(): ScaleBarMeasure? =
  macosSystemDefaultPrimaryMeasure()

internal fun macosSystemDefaultPrimaryMeasure(): ScaleBarMeasure? {
  if (!System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)) return null
  return runCatching {
    ObjectiveC.runInAutoreleasePool {
      val locale = ObjectiveC.sendClassPointer("NSLocale", "currentLocale")
      val key = ObjectiveC.nsString("kCFLocaleMeasurementSystemKey")
      val value = ObjectiveC.sendPointer(locale, "objectForKey:", key)
      scaleBarMeasureForAppleMeasurementSystem(ObjectiveC.utf8String(value))
    }
  }
    .getOrNull()
}
