package org.maplibre.compose.interaction.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.interaction.SnappingBuilder

class SnappingTest {
  private fun snap(spacing: Double, offset: Double, tolerance: Double) =
    Snapping(true, spacing, offset, tolerance, Duration.ZERO)

  @Test
  fun bearing_targets_wrap_and_take_the_shorter_way_around() {
    val north = snap(360.0, 0.0, 7.0)
    assertEquals(-3.0, north.delta(3.0, circular = true))
    assertEquals(7.0, north.delta(353.0, circular = true))
    assertEquals(0.0, north.delta(352.9, circular = true))
    assertEquals(-3.0, north.delta(723.0, circular = true))
    assertEquals(3.0, north.delta(-3.0, circular = true))
  }

  @Test
  fun bearing_targets_lie_every_spacing_from_the_offset_within_one_turn() {
    val quarters = snap(90.0, 32.0, 45.0)
    assertEquals(2.0, quarters.delta(120.0, circular = true))
    assertEquals(-2.0, quarters.delta(214.0, circular = true))
    // Equidistant targets choose the one below.
    assertEquals(-45.0, quarters.delta(77.0, circular = true))
    assertEquals(2.0, quarters.delta(300.0, circular = true))
    // A spacing that doesn't divide the turn leaves a shorter gap before the offset comes again.
    val uneven = snap(100.0, 0.0, 30.0)
    assertEquals(-5.0, uneven.delta(305.0, circular = true))
    assertEquals(25.0, uneven.delta(335.0, circular = true))
  }

  @Test
  fun zoom_and_pitch_targets_round_to_the_nearest_without_wrapping() {
    val levels = snap(1.0, 0.0, 0.5)
    assertEquals(0.25, levels.delta(4.75, circular = false), 1e-12)
    assertEquals(-0.25, levels.delta(4.25, circular = false), 1e-12)
    val flat = snap(90.0, 0.0, 7.0)
    assertEquals(-5.0, flat.delta(5.0, circular = false))
    assertEquals(0.0, flat.delta(30.0, circular = false))
  }

  @Test
  fun only_rotation_snaps_by_default_and_a_block_turns_snapping_on() {
    val defaults = MapInteractions.Standard.camera.settings
    assertTrue(defaults.rotate.snapping.enabled)
    assertEquals(-3.0, defaults.rotate.snapping.delta(3.0, circular = true))
    assertEquals(300.milliseconds, defaults.rotate.snapping.duration)
    assertFalse(defaults.zoom.snapping.enabled)
    assertFalse(defaults.pitch.snapping.enabled)

    val configured = MapInteractions {
      camera {
        zoom { snapping() }
        rotate { snapping { offset = 32.0 } }
      }
    }
    assertTrue(configured.camera.settings.zoom.snapping.enabled)
    assertEquals(0.5, configured.camera.settings.zoom.snapping.tolerance)
    assertEquals(2.0, configured.camera.settings.rotate.snapping.delta(30.0, circular = true))

    val disabled =
      MapInteractions(configured) { camera { rotate { snapping { enabled = false } } } }
    assertFalse(disabled.camera.settings.rotate.snapping.enabled)
    val restored = MapInteractions(disabled) { camera { rotate { snapping() } } }
    assertEquals(2.0, restored.camera.settings.rotate.snapping.delta(30.0, circular = true))
  }

  @Test
  fun invalid_snapping_is_rejected() {
    val invalid: List<SnappingBuilder.() -> Unit> =
      listOf(
        { spacing = 0.0 },
        { spacing = Double.POSITIVE_INFINITY },
        { offset = Double.NaN },
        { tolerance = -1.0 },
        { tolerance = Double.NaN },
        { duration = (-1).milliseconds },
        { duration = Duration.INFINITE },
      )
    for (block in invalid) {
      assertFailsWith<IllegalArgumentException> {
        MapInteractions { camera { rotate { snapping(block) } } }
      }
    }
  }
}
