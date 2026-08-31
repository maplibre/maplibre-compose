package org.maplibre.compose.overlay

import kotlin.test.Test
import kotlin.test.assertNotNull

class ScaleBarMeasureTest {
  @Test
  fun macosReadsTheCurrentFoundationMeasurementSystem() {
    if (!System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)) return
    assertNotNull(macosSystemDefaultPrimaryMeasure())
  }
}
