package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class TileLodAlgorithmTest {
  @Test
  fun presets_and_edits_compare_by_algorithm_and_applicable_parameters() {
    assertEquals(TileLodAlgorithm.ScreenCenter(), TileLodOptions.Standard.algorithm)
    assertEquals(
      TileLodAlgorithm.ScreenCenter {
        minRadius = 2.0
        scale = 1.5
        pitchThreshold = 45.0
        zoomShift = -1.0
      },
      TileLodOptions.Performance.algorithm,
    )
    assertEquals(
      TileLodAlgorithm.ScreenCenter {
        minRadius = 5.0
        pitchThreshold = 85.0
      },
      TileLodOptions.HighDetail.algorithm,
    )
    val distance = TileLodAlgorithm.CameraDistance {
      scale = 2.0
      pitchThreshold = 30.0
      zoomShift = -1.0
    }
    val options = TileLodOptions(TileLodOptions.Performance) { algorithm = distance }
    val equal = TileLodOptions { algorithm = TileLodAlgorithm.CameraDistance(distance) {} }
    assertEquals(options, equal)
    assertEquals(options.hashCode(), equal.hashCode())
    assertEquals(options, TileLodOptions(options) {})
    assertNotEquals(
      options,
      TileLodOptions { algorithm = TileLodAlgorithm.CameraDistance(distance) { scale = 3.0 } },
    )
    assertNotEquals(
      options,
      TileLodOptions {
        algorithm = TileLodAlgorithm.CameraDistance(distance) { pitchThreshold = 40.0 }
      },
    )
    assertNotEquals(
      options,
      TileLodOptions { algorithm = TileLodAlgorithm.CameraDistance(distance) { zoomShift = 0.0 } },
    )
    assertNotEquals(
      TileLodOptions.Standard,
      TileLodOptions { algorithm = TileLodAlgorithm.CameraDistance() },
    )
    assertEquals(
      TileLodOptions.Standard,
      TileLodOptions(options) { algorithm = TileLodAlgorithm.ScreenCenter() },
    )
  }

  @Test
  fun edits_inherit_settings_and_retained_builders_cannot_mutate_values() {
    lateinit var screenCenterBuilder: TileLodAlgorithm.ScreenCenter.Builder
    val original = TileLodAlgorithm.ScreenCenter {
      screenCenterBuilder = this
      minRadius = 4.0
      scale = 2.0
      pitchThreshold = 30.0
      zoomShift = -1.0
    }
    screenCenterBuilder.scale = 9.0
    val edited = TileLodAlgorithm.ScreenCenter(original) { minRadius = 5.0 }
    assertEquals(4.0, original.minRadius)
    assertEquals(2.0, original.scale)
    assertEquals(5.0, edited.minRadius)
    assertEquals(2.0, edited.scale)
    assertEquals(30.0, edited.pitchThreshold)
    assertEquals(-1.0, edited.zoomShift)

    lateinit var optionsBuilder: TileLodOptions.Builder
    val options = TileLodOptions {
      optionsBuilder = this
      algorithm = original
    }
    optionsBuilder.algorithm = edited
    assertEquals(original, options.algorithm)
    assertEquals(options, TileLodOptions(options) {})

    lateinit var distanceBuilder: TileLodAlgorithm.CameraDistance.Builder
    val distance = TileLodAlgorithm.CameraDistance {
      distanceBuilder = this
      scale = 2.0
      pitchThreshold = 30.0
      zoomShift = -1.0
    }
    distanceBuilder.scale = 9.0
    val distanceEdit = TileLodAlgorithm.CameraDistance(distance) { pitchThreshold = 45.0 }
    assertEquals(2.0, distance.scale)
    assertEquals(30.0, distance.pitchThreshold)
    assertEquals(2.0, distanceEdit.scale)
    assertEquals(45.0, distanceEdit.pitchThreshold)
    assertEquals(-1.0, distanceEdit.zoomShift)
  }

  @Test
  fun invalid_parameters_fail_before_reaching_the_engine() {
    for (invalid in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
      assertFailsWith<IllegalArgumentException> {
        TileLodAlgorithm.ScreenCenter { minRadius = invalid }
      }
      assertFailsWith<IllegalArgumentException> {
        TileLodAlgorithm.ScreenCenter { scale = invalid }
      }
      assertFailsWith<IllegalArgumentException> {
        TileLodAlgorithm.CameraDistance { scale = invalid }
      }
      assertFailsWith<IllegalArgumentException> {
        TileLodAlgorithm.ScreenCenter { pitchThreshold = invalid }
      }
      assertFailsWith<IllegalArgumentException> {
        TileLodAlgorithm.CameraDistance { pitchThreshold = invalid }
      }
      assertFailsWith<IllegalArgumentException> {
        TileLodAlgorithm.ScreenCenter { zoomShift = invalid }
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
        TileLodAlgorithm.ScreenCenter { pitchThreshold = invalid }
      }
      assertFailsWith<IllegalArgumentException> {
        TileLodAlgorithm.CameraDistance { pitchThreshold = invalid }
      }
    }
    // Engine boundary values remain available, including disabling distance-based coarsening.
    TileLodAlgorithm.ScreenCenter {
      minRadius = 1.0
      scale = 0.0
      pitchThreshold = 0.0
    }
    TileLodAlgorithm.CameraDistance {
      scale = 0.0
      pitchThreshold = 180.0
    }
    assertFailsWith<IllegalArgumentException> {
      TileLodAlgorithm.ScreenCenter(TileLodAlgorithm.ScreenCenter()) { minRadius = 0.0 }
    }
    assertFailsWith<IllegalArgumentException> {
      TileLodAlgorithm.CameraDistance(TileLodAlgorithm.CameraDistance()) { scale = Double.NaN }
    }
  }
}
