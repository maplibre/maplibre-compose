package org.maplibre.compose.interaction.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.interaction.BearingHapticNotch
import org.maplibre.compose.interaction.BearingTargets
import org.maplibre.compose.interaction.HapticEmphasis
import org.maplibre.compose.interaction.MapInteractions

class BearingHapticDetectorTest {
  private fun north() =
    BearingHapticDetector(
      listOf(BearingHapticNotch(BearingTargets.at(0.0), HapticEmphasis.Standard))
    )

  @Test
  fun starting_at_a_notch_is_silent_and_jitter_requires_leaving_before_reentry() {
    val detector = north()
    assertNull(detector.update(0.0, 0.5, 0.milliseconds))
    assertNull(detector.update(0.5, -0.5, 100.milliseconds))
    assertNull(detector.update(-0.5, -3.0, 200.milliseconds))
    assertEquals(HapticEmphasis.Standard, detector.update(-3.0, -0.5, 300.milliseconds))
    assertNull(detector.update(-0.5, 0.5, 400.milliseconds))
    assertNull(detector.update(0.5, -0.5, 500.milliseconds))
  }

  @Test
  fun detects_crossings_between_samples_in_both_directions_across_north() {
    val detector = north()
    assertEquals(HapticEmphasis.Standard, detector.update(355.0, 5.0, 0.milliseconds))
    assertEquals(HapticEmphasis.Standard, detector.update(5.0, 355.0, 100.milliseconds))
  }

  @Test
  fun custom_offsets_and_layered_notches_emit_only_the_strongest_tick() {
    val detector =
      BearingHapticDetector(
        listOf(
          BearingHapticNotch(BearingTargets.evenlySpaced(24, 32.0), HapticEmphasis.Subtle),
          BearingHapticNotch(BearingTargets.evenlySpaced(4, 32.0), HapticEmphasis.Standard),
          BearingHapticNotch(BearingTargets.at(32.0), HapticEmphasis.Emphasized),
        )
      )
    assertEquals(HapticEmphasis.Emphasized, detector.update(28.0, 34.0, 0.milliseconds))
    assertEquals(HapticEmphasis.Subtle, detector.update(34.0, 49.0, 100.milliseconds))
    assertEquals(HapticEmphasis.Standard, detector.update(49.0, 124.0, 200.milliseconds))
  }

  @Test
  fun fast_rotation_coalesces_crossings_without_replaying_suppressed_ticks() {
    val detector = north()
    assertEquals(HapticEmphasis.Standard, detector.update(-5.0, 5.0, 0.milliseconds))
    assertNull(detector.update(5.0, 0.5, 10.milliseconds))
    assertNull(detector.update(0.5, -0.5, 100.milliseconds))
    assertNull(detector.update(-0.5, -5.0, 200.milliseconds))
    assertEquals(HapticEmphasis.Standard, detector.update(-5.0, 5.0, 300.milliseconds))
  }

  @Test
  fun haptic_blocks_replace_inherited_notches_without_enabling_snapping() {
    val north = MapInteractions { camera { rotate { haptics { notch(BearingTargets.at(0.0)) } } } }
    assertEquals(false, north.camera.settings.rotate.snapping.enabled)
    val other =
      MapInteractions(north) { camera { rotate { haptics { notch(BearingTargets.at(32.0)) } } } }
    assertEquals(
      listOf(BearingHapticNotch(BearingTargets.at(32.0), HapticEmphasis.Standard)),
      other.camera.settings.rotate.haptics,
    )
    assertEquals(
      emptyList(),
      MapInteractions(north) { camera { rotate { haptics {} } } }.camera.settings.rotate.haptics,
    )
  }
}
