package org.maplibre.compose.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

  @Test
  fun linuxReadsTheGlibcMeasurementCategory() {
    assumeTrue(
      "Requires Linux glibc",
      System.getProperty("os.name").orEmpty().startsWith("Linux", ignoreCase = true),
    )
    assertEquals(ScaleBarMeasure.Metric, linuxSystemDefaultPrimaryMeasure("C"))
    assertNull(linuxSystemDefaultPrimaryMeasure("xx_YY"))
    val us = linuxSystemDefaultPrimaryMeasure("en_US.UTF-8")
    assumeTrue("Requires the en_US.UTF-8 locale", us != null)
    assertEquals(ScaleBarMeasure.FeetAndMiles, us)
  }

  @Test
  fun windowsReadsTheLocaleMeasurementSystem() {
    assumeTrue(
      "Requires Windows",
      System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true),
    )
    assertNotNull(windowsSystemDefaultPrimaryMeasure())
  }
}
