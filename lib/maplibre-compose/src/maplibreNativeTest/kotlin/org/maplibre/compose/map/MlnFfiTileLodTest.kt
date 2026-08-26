package org.maplibre.compose.map

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.nativeffi.map.TileLodMode as FfiTileLodMode

class MlnFfiTileLodTest {

  @Test
  fun performance_options_reach_the_map_and_leave_prefetch_alone() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)

      fixture.core.readMap { map ->
        map.tileOptions = map.tileOptions.also { it.prefetchZoomDelta = PREFETCH }
      }
      fixture.core.setTileLodSettings(TileLodOptions.Performance)
      val applied = assertNotNull(fixture.core.readMap { it.tileOptions })

      assertEquals(FfiTileLodMode.DEFAULT, applied.lodMode)
      assertEquals(2.0, assertNotNull(applied.lodMinRadius))
      assertEquals(1.5, assertNotNull(applied.lodScale))
      assertAngleDegrees(45.0, applied.lodPitchThreshold)
      assertEquals(-1.0, assertNotNull(applied.lodZoomShift))
      assertEquals(PREFETCH, applied.prefetchZoomDelta)
    }
  }

  @Test
  fun distance_mode_and_a_return_to_standard_both_round_trip() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)

      fixture.core.setTileLodSettings(
        TileLodOptions(
          mode = TileLodMode.Distance,
          minRadius = 4.0,
          scale = 2.0,
          pitchThreshold = 30.0,
          zoomShift = 1.0,
        )
      )
      val appliedDistance = assertNotNull(fixture.core.readMap { it.tileOptions })

      assertEquals(FfiTileLodMode.DISTANCE, appliedDistance.lodMode)
      assertEquals(2.0, assertNotNull(appliedDistance.lodScale))
      assertAngleDegrees(30.0, appliedDistance.lodPitchThreshold)

      fixture.core.setTileLodSettings(TileLodOptions.Standard)
      val appliedStandard = assertNotNull(fixture.core.readMap { it.tileOptions })

      assertEquals(FfiTileLodMode.DEFAULT, appliedStandard.lodMode)
      assertEquals(3.0, assertNotNull(appliedStandard.lodMinRadius))
      assertEquals(1.0, assertNotNull(appliedStandard.lodScale))
      assertAngleDegrees(60.0, appliedStandard.lodPitchThreshold)
      assertEquals(0.0, assertNotNull(appliedStandard.lodZoomShift))
    }
  }

  private companion object {
    const val PREFETCH = 7

    fun assertAngleDegrees(expected: Double, radians: Double?) {
      val actual = assertNotNull(radians) * 180.0 / PI
      assertTrue(abs(expected - actual) <= 1e-6, "expected $expected°, was $actual°")
    }
  }
}
