package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class GeoJsonOptionsTest {
  @Test
  fun shouldDefaultSynchronousUpdateToFalse() {
    assertFalse(GeoJsonOptions().synchronousUpdate)
  }

  @Test
  fun shouldIncludeSynchronousUpdateInEquality() {
    assertEquals(GeoJsonOptions(synchronousUpdate = true), GeoJsonOptions(synchronousUpdate = true))
    assertNotEquals(GeoJsonOptions(), GeoJsonOptions(synchronousUpdate = true))
  }
}
