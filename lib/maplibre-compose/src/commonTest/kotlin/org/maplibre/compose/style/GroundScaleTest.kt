package org.maplibre.compose.style

import kotlin.math.PI
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroundScaleTest {
  @Test
  fun globe_latitudes_past_the_mercator_limit_keep_shrinking_the_scale() {
    val equator = groundScale(latitude = 0.0)
    assertEquals(equator * cos(89.0 * PI / 180).toFloat(), groundScale(latitude = 89.0), 0.01f)
    assertTrue(groundScale(latitude = 90.0) > 0f)
  }
}
