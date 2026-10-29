package org.maplibre.compose.map

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.runMapTest
import org.maplibre.nativeffi.MapTileOptions
import org.maplibre.nativeffi.TileLodMode as FfiTileLodMode

class MlnFfiTileLodTest {

  @Test
  fun performance_options_reach_the_map_and_leave_prefetch_alone() = runMapTest {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)

      fixture.session.readMap { map ->
        map.setTileOptions(MapTileOptions(prefetchZoomDelta = Prefetch)).await()
      }
      fixture.session.setTileLodSettings(TileLodOptions.Performance)
      val applied = assertNotNull(fixture.session.readMap { it.tileOptions() })

      assertEquals(FfiTileLodMode.DEFAULT, applied.lodMode)
      assertEquals(2.0, assertNotNull(applied.lodMinRadius))
      assertEquals(1.5, assertNotNull(applied.lodScale))
      assertAngleDegrees(45.0, applied.lodPitchThreshold)
      assertEquals(-1.0, assertNotNull(applied.lodZoomShift))
      assertEquals(Prefetch, applied.prefetchZoomDelta)
    }
  }

  @Test
  fun distance_algorithm_replaces_previous_settings_and_a_return_to_standard_round_trips() =
    runMapTest {
      BridgeMapFixture.create().use { fixture ->
        fixture.loadStyle(BaseStyle.Empty)
        fixture.session.setTileLodSettings(TileLodOptions.Performance)

        val distance = TileLodAlgorithm.CameraDistance {
          scale = 2.0
          pitchThreshold = 45.0
          zoomShift = 1.0
        }
        fixture.session.setTileLodSettings(
          TileLodOptions(TileLodOptions.Performance) {
            algorithm = TileLodAlgorithm.CameraDistance(distance) { pitchThreshold = 30.0 }
          },
        )
        val appliedDistance = assertNotNull(fixture.session.readMap { it.tileOptions() })

        assertEquals(FfiTileLodMode.DISTANCE, appliedDistance.lodMode)
        assertEquals(3.0, assertNotNull(appliedDistance.lodMinRadius))
        assertEquals(1.0, assertNotNull(appliedDistance.lodZoomShift))
        assertEquals(2.0, assertNotNull(appliedDistance.lodScale))
        assertAngleDegrees(30.0, appliedDistance.lodPitchThreshold)

        fixture.session.setTileLodSettings(TileLodOptions.Standard)
        val appliedStandard = assertNotNull(fixture.session.readMap { it.tileOptions() })

        assertEquals(FfiTileLodMode.DEFAULT, appliedStandard.lodMode)
        assertEquals(3.0, assertNotNull(appliedStandard.lodMinRadius))
        assertEquals(1.0, assertNotNull(appliedStandard.lodScale))
        assertAngleDegrees(60.0, appliedStandard.lodPitchThreshold)
        assertEquals(0.0, assertNotNull(appliedStandard.lodZoomShift))
      }
    }

  @Test
  fun invalid_parameters_fail_at_construction_and_engine_boundary_values_remain_available() {
    for (invalid in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
      assertFailsWith<IllegalArgumentException> {
        TileLodAlgorithm.ScreenCenter { minRadius = invalid }
      }
      assertFailsWith<IllegalArgumentException> {
        TileLodAlgorithm.CameraDistance { scale = invalid }
      }
      assertFailsWith<IllegalArgumentException> {
        TileLodAlgorithm.CameraDistance { pitchThreshold = invalid }
      }
      assertFailsWith<IllegalArgumentException> {
        TileLodAlgorithm.CameraDistance { zoomShift = invalid }
      }
    }
    assertFailsWith<IllegalArgumentException> { TileLodAlgorithm.ScreenCenter { minRadius = 0.9 } }
    assertFailsWith<IllegalArgumentException> { TileLodAlgorithm.ScreenCenter { scale = -1.0 } }
    assertFailsWith<IllegalArgumentException> { TileLodAlgorithm.CameraDistance { scale = -1.0 } }
    for (invalid in listOf(-1.0, 181.0)) {
      assertFailsWith<IllegalArgumentException> {
        TileLodAlgorithm.CameraDistance { pitchThreshold = invalid }
      }
    }
    TileLodAlgorithm.ScreenCenter {
      minRadius = 1.0
      scale = 0.0
      pitchThreshold = 0.0
    }
    TileLodAlgorithm.CameraDistance {
      scale = 0.0
      pitchThreshold = 180.0
    }
  }

  private companion object {
    val Prefetch = 7u

    fun assertAngleDegrees(expected: Double, radians: Double?) {
      val actual = assertNotNull(radians) * 180.0 / PI
      assertTrue(abs(expected - actual) <= 1e-6, "expected $expected°, was $actual°")
    }
  }
}
