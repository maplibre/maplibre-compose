@file:OptIn(ExperimentalForeignApi::class)

package org.maplibre.compose.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleMeasurementSystem

class ScaleBarMeasureTest {
  @Test
  fun localeMeasurementSystemOverridesAreVisibleThroughFoundation() {
    assertEquals(ScaleBarMeasure.Metric, measureFor("en-US-u-ms-metric"))
    assertEquals(ScaleBarMeasure.FeetAndMiles, measureFor("de-DE-u-ms-ussystem"))
    assertEquals(ScaleBarMeasure.YardsAndMiles, measureFor("en-US-u-ms-uksystem"))
  }

  private fun measureFor(localeIdentifier: String): ScaleBarMeasure? {
    val locale = NSLocale(localeIdentifier)
    val measurementSystem = locale.objectForKey(NSLocaleMeasurementSystem) as? String
    return scaleBarMeasureForAppleMeasurementSystem(measurementSystem)
  }
}
