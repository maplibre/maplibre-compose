package org.maplibre.compose.camera

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

class ViewportTest {
  private fun createViewport(
    width: Double = 100.0,
    height: Double = 200.0,
    minLon: Double = -10.0,
    minLat: Double = -20.0,
    maxLon: Double = 10.0,
    maxLat: Double = 20.0,
    metersPerDp: Double = 5.0,
  ): Viewport =
    Viewport(
      size = DpSize(width.dp, height.dp),
      visibleBoundingBox =
        BoundingBox(
          southwest = Position(longitude = minLon, latitude = minLat),
          northeast = Position(longitude = maxLon, latitude = maxLat),
        ),
      visibleRegion =
        VisibleRegion(
          farLeft = Position(longitude = minLon, latitude = maxLat),
          farRight = Position(longitude = maxLon, latitude = maxLat),
          nearLeft = Position(longitude = minLon, latitude = minLat),
          nearRight = Position(longitude = maxLon, latitude = minLat),
        ),
      metersPerDpAtTarget = metersPerDp,
    )

  @Test
  fun testValueEquality() {
    val v1 = createViewport()
    val v2 = createViewport()
    assertEquals(v1, v2)
    assertEquals(v1.hashCode(), v2.hashCode())
  }

  @Test
  fun testInequalityOnDifference() {
    val v1 = createViewport(width = 100.0)
    val v2 = createViewport(width = 200.0)
    assertNotEquals(v1, v2)
  }

  @Test
  fun testCopyAndDestructuring() {
    val v = createViewport()
    val copy = v.copy(metersPerDpAtTarget = 10.0)
    assertNotEquals(v, copy)
    assertEquals(10.0, copy.metersPerDpAtTarget)

    val (size, boundingBox, visibleRegion, metersPerDp) = v
    assertEquals(v.size, size)
    assertEquals(v.visibleBoundingBox, boundingBox)
    assertEquals(v.visibleRegion, visibleRegion)
    assertEquals(v.metersPerDpAtTarget, metersPerDp)
  }
}
