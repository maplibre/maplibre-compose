package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class TileLodAlgorithmTest {
  @Test
  fun presets_and_edits_compare_by_algorithm_and_applicable_parameters() {
    assertEquals(TileLodAlgorithm.Default(), TileLodOptions.Standard.algorithm)
    assertEquals(
      TileLodAlgorithm.Default {
        minRadius = 2.0
        scale = 1.5
        pitchThreshold = 45.0
        zoomShift = -1.0
      },
      TileLodOptions.Performance.algorithm,
    )
    assertEquals(
      TileLodAlgorithm.Default {
        minRadius = 5.0
        pitchThreshold = 85.0
      },
      TileLodOptions.HighDetail.algorithm,
    )
    val distance = TileLodAlgorithm.Distance {
      scale = 2.0
      pitchThreshold = 30.0
      zoomShift = -1.0
    }
    val options = TileLodOptions(TileLodOptions.Performance) { algorithm = distance }
    val equal = TileLodOptions { algorithm = TileLodAlgorithm.Distance(distance) {} }
    assertEquals(options, equal)
    assertEquals(options.hashCode(), equal.hashCode())
    assertEquals(options, TileLodOptions(options) {})
    assertNotEquals(
      options,
      TileLodOptions { algorithm = TileLodAlgorithm.Distance(distance) { scale = 3.0 } },
    )
    assertNotEquals(
      options,
      TileLodOptions { algorithm = TileLodAlgorithm.Distance(distance) { pitchThreshold = 40.0 } },
    )
    assertNotEquals(
      options,
      TileLodOptions { algorithm = TileLodAlgorithm.Distance(distance) { zoomShift = 0.0 } },
    )
    assertNotEquals(
      TileLodOptions.Standard,
      TileLodOptions { algorithm = TileLodAlgorithm.Distance() },
    )
    assertEquals(
      TileLodOptions.Standard,
      TileLodOptions(options) { algorithm = TileLodAlgorithm.Default() },
    )
  }

  @Test
  fun edits_inherit_settings_and_retained_builders_cannot_mutate_values() {
    lateinit var defaultBuilder: TileLodAlgorithm.Default.Builder
    val original = TileLodAlgorithm.Default {
      defaultBuilder = this
      minRadius = 4.0
      scale = 2.0
      pitchThreshold = 30.0
      zoomShift = -1.0
    }
    defaultBuilder.scale = 9.0
    val edited = TileLodAlgorithm.Default(original) { minRadius = 5.0 }
    assertEquals(4.0, original.minRadius)
    assertEquals(2.0, original.scale)
    assertEquals(5.0, edited.minRadius)
    assertEquals(2.0, edited.scale)
    assertEquals(30.0, edited.pitchThreshold)
    assertEquals(-1.0, edited.zoomShift)

    lateinit var distanceBuilder: TileLodAlgorithm.Distance.Builder
    val distance = TileLodAlgorithm.Distance {
      distanceBuilder = this
      scale = 2.0
      pitchThreshold = 30.0
      zoomShift = -1.0
    }
    distanceBuilder.scale = 9.0
    val distanceEdit = TileLodAlgorithm.Distance(distance) { pitchThreshold = 45.0 }
    assertEquals(2.0, distance.scale)
    assertEquals(30.0, distance.pitchThreshold)
    assertEquals(2.0, distanceEdit.scale)
    assertEquals(45.0, distanceEdit.pitchThreshold)
    assertEquals(-1.0, distanceEdit.zoomShift)
  }

  @Test
  fun invalid_parameters_fail_before_reaching_the_engine() {
    for (invalid in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
      assertFailsWith<IllegalArgumentException> { TileLodAlgorithm.Default { minRadius = invalid } }
      assertFailsWith<IllegalArgumentException> { TileLodAlgorithm.Default { scale = invalid } }
      assertFailsWith<IllegalArgumentException> { TileLodAlgorithm.Distance { scale = invalid } }
      assertFailsWith<IllegalArgumentException> {
        TileLodAlgorithm.Default { pitchThreshold = invalid }
      }
      assertFailsWith<IllegalArgumentException> {
        TileLodAlgorithm.Distance { pitchThreshold = invalid }
      }
      assertFailsWith<IllegalArgumentException> { TileLodAlgorithm.Default { zoomShift = invalid } }
      assertFailsWith<IllegalArgumentException> {
        TileLodAlgorithm.Distance { zoomShift = invalid }
      }
    }
    assertFailsWith<IllegalArgumentException> { TileLodAlgorithm.Default { minRadius = 0.9 } }
    assertFailsWith<IllegalArgumentException> { TileLodAlgorithm.Default { scale = -1.0 } }
    assertFailsWith<IllegalArgumentException> { TileLodAlgorithm.Distance { scale = -1.0 } }
    for (invalid in listOf(-1.0, 181.0)) {
      assertFailsWith<IllegalArgumentException> {
        TileLodAlgorithm.Default { pitchThreshold = invalid }
      }
      assertFailsWith<IllegalArgumentException> {
        TileLodAlgorithm.Distance { pitchThreshold = invalid }
      }
    }
    // Engine boundary values remain available, including disabling distance-based coarsening.
    TileLodAlgorithm.Default {
      minRadius = 1.0
      scale = 0.0
      pitchThreshold = 0.0
    }
    TileLodAlgorithm.Distance {
      scale = 0.0
      pitchThreshold = 180.0
    }
    assertFailsWith<IllegalArgumentException> {
      TileLodAlgorithm.Default(TileLodAlgorithm.Default()) { minRadius = 0.0 }
    }
    assertFailsWith<IllegalArgumentException> {
      TileLodAlgorithm.Distance(TileLodAlgorithm.Distance()) { scale = Double.NaN }
    }
  }
}
