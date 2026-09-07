package org.maplibre.compose.interaction.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.maplibre.compose.interaction.BearingSnapping
import org.maplibre.compose.interaction.BearingTargets
import org.maplibre.compose.interaction.MapInteractions

class BearingSnappingTest {
  @Test
  fun custom_targets_use_circular_distance_and_leave_other_bearings_free() {
    val snap = BearingSnapping(true, BearingTargets.at(32.0, 358.0), 7.0)
    assertEquals(-5.0, snap.delta(3.0))
    assertEquals(7.0, snap.delta(25.0))
    assertNull(snap.delta(24.9))
    assertNull(snap.delta(32.0))
    assertEquals(-5.0, snap.delta(723.0))
  }

  @Test
  fun evenly_spaced_targets_support_offsets_and_deterministic_ties() {
    val snap = BearingSnapping(true, BearingTargets.evenlySpaced(4, 32.0), 45.0)
    assertEquals(2.0, snap.delta(120.0))
    assertEquals(-2.0, snap.delta(214.0))
    assertEquals(-45.0, snap.delta(77.0))
    assertEquals(2.0, snap.delta(300.0))
  }

  @Test
  fun targets_copy_and_normalize_caller_input() {
    val input = doubleArrayOf(-2.0, 358.0, 32.0)
    val targets = BearingTargets.at(*input)
    input[0] = 100.0
    assertEquals(BearingTargets.at(32.0, 358.0), targets)
    assertEquals(BearingTargets.at(0.0, 90.0, 180.0, 270.0), BearingTargets.evenlySpaced(4))
    assertEquals(BearingTargets.at(12.0), BearingTargets.evenlySpaced(1, 372.0))
  }

  @Test
  fun snapping_defaults_to_north_and_can_disable_inherited_settings() {
    val defaults = MapInteractions.Standard.camera.settings.rotate.snapping
    assertEquals(-3.0, defaults.delta(3.0))
    assertEquals(7.0, defaults.delta(353.0))
    assertNull(defaults.delta(352.9))
    assertNull(defaults.delta(90.0))
    val configured = MapInteractions {
      camera { rotate { snapping { targets = BearingTargets.at(32.0) } } }
    }
    assertEquals(2.0, configured.camera.settings.rotate.snapping.delta(30.0))
    val disabled =
      MapInteractions(configured) { camera { rotate { snapping { enabled = false } } } }
    assertNull(disabled.camera.settings.rotate.snapping.delta(30.0))
    assertEquals(
      2.0,
      MapInteractions(disabled) { camera { rotate { snapping() } } }
        .camera
        .settings
        .rotate
        .snapping
        .delta(30.0),
    )
  }

  @Test
  fun invalid_targets_and_tolerances_are_rejected() {
    assertFailsWith<IllegalArgumentException> { BearingTargets.at() }
    assertFailsWith<IllegalArgumentException> { BearingTargets.at(Double.NaN) }
    assertFailsWith<IllegalArgumentException> { BearingTargets.evenlySpaced(0) }
    assertFailsWith<IllegalArgumentException> {
      BearingTargets.evenlySpaced(4, Double.POSITIVE_INFINITY)
    }
    for (value in listOf(-1.0, 181.0, Double.NaN)) {
      assertFailsWith<IllegalArgumentException> {
        MapInteractions { camera { rotate { snapping { tolerance = value } } } }
      }
    }
  }
}
