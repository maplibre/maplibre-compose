package org.maplibre.compose.overlay

import kotlin.test.Test
import kotlin.test.assertNotNull
import org.junit.Assume.assumeTrue

class ScaleBarMeasureTest {
  @Test
  fun macosReadsTheCurrentFoundationMeasurementSystem() {
    assumeTrue(
      "Requires macOS Foundation",
      System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true),
    )
    assertNotNull(macosSystemDefaultPrimaryMeasure())
  }
}
