package org.maplibre.compose.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScaleBarMeasuresTest {
  @Test
  fun appleMeasurementSystemsMapToScaleBarMeasures() {
    assertEquals(
      ScaleBarMeasure.Metric,
      scaleBarMeasureForAppleMeasurementSystem("Metric"),
    )
    assertEquals(
      ScaleBarMeasure.FeetAndMiles,
      scaleBarMeasureForAppleMeasurementSystem("U.S."),
    )
    assertEquals(
      ScaleBarMeasure.YardsAndMiles,
      scaleBarMeasureForAppleMeasurementSystem("U.K."),
    )
  }

  @Test
  fun unknownAppleMeasurementSystemFallsBackToTheLocale() {
    assertNull(scaleBarMeasureForAppleMeasurementSystem(null))
    assertNull(scaleBarMeasureForAppleMeasurementSystem("Nautical"))
  }
}
