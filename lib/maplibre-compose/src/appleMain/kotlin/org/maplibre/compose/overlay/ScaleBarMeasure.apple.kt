package org.maplibre.compose.overlay

import androidx.compose.runtime.Composable
import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleMeasurementSystem
import platform.Foundation.currentLocale

@Composable
internal actual fun systemDefaultPrimaryMeasure(): ScaleBarMeasure? {
  val measurementSystem = NSLocale.currentLocale.objectForKey(NSLocaleMeasurementSystem) as? String
  return scaleBarMeasureForAppleMeasurementSystem(measurementSystem)
}
