package org.maplibre.compose.interaction.internal

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.interaction.BearingHapticNotch
import org.maplibre.compose.interaction.MapInteractions

class BearingHapticDetectorTest {
  private fun north() =
    BearingHapticDetector(
      listOf(BearingHapticNotch(360.0, 0.0, HapticFeedbackType.SegmentTick)),
    )

  @Test
  fun starting_at_a_notch_is_silent_and_jitter_requires_leaving_before_reentry() {
    val detector = north()
    assertNull(detector.update(0.0, 0.5, 0.milliseconds))
    assertNull(detector.update(0.5, -0.5, 100.milliseconds))
    assertNull(detector.update(-0.5, -3.0, 200.milliseconds))
    assertEquals(HapticFeedbackType.SegmentTick, detector.update(-3.0, -0.5, 300.milliseconds))
    assertNull(detector.update(-0.5, 0.5, 400.milliseconds))
    assertNull(detector.update(0.5, -0.5, 500.milliseconds))
  }

  @Test
  fun detects_crossings_between_samples_in_both_directions_across_north() {
    val detector = north()
    assertEquals(HapticFeedbackType.SegmentTick, detector.update(355.0, 5.0, 0.milliseconds))
    assertEquals(HapticFeedbackType.SegmentTick, detector.update(5.0, 355.0, 100.milliseconds))
  }

  @Test
  fun custom_offsets_and_layered_notches_emit_only_the_first_declared_tick() {
    val detector =
      BearingHapticDetector(
        listOf(
          BearingHapticNotch(360.0, 32.0, HapticFeedbackType.ContextClick),
          BearingHapticNotch(90.0, 32.0, HapticFeedbackType.SegmentTick),
          BearingHapticNotch(15.0, 32.0, HapticFeedbackType.SegmentFrequentTick),
        ),
      )
    assertEquals(HapticFeedbackType.ContextClick, detector.update(28.0, 34.0, 0.milliseconds))
    assertEquals(
      HapticFeedbackType.SegmentFrequentTick,
      detector.update(34.0, 49.0, 100.milliseconds),
    )
    assertEquals(HapticFeedbackType.SegmentTick, detector.update(49.0, 124.0, 200.milliseconds))
  }

  @Test
  fun fast_rotation_coalesces_crossings_without_replaying_suppressed_ticks() {
    val detector = north()
    assertEquals(HapticFeedbackType.SegmentTick, detector.update(-5.0, 5.0, 0.milliseconds))
    assertNull(detector.update(5.0, 0.5, 10.milliseconds))
    assertNull(detector.update(0.5, -0.5, 100.milliseconds))
    assertNull(detector.update(-0.5, -5.0, 200.milliseconds))
    assertEquals(HapticFeedbackType.SegmentTick, detector.update(-5.0, 5.0, 300.milliseconds))
  }

  @Test
  fun haptic_blocks_replace_inherited_notches_without_enabling_snapping() {
    val defaults = MapInteractions.Standard.camera.settings.rotate.haptics
    val detector = BearingHapticDetector(defaults)
    assertEquals(HapticFeedbackType.SegmentTick, detector.update(355.0, 5.0, 0.milliseconds))
    val north = MapInteractions {
      camera {
        rotate {
          snapping { enabled = false }
          haptics { notch(spacing = 360.0) }
        }
      }
    }
    assertEquals(false, north.camera.settings.rotate.snapping.enabled)
    val other =
      MapInteractions(north) { camera { rotate { haptics { notch(360.0, offset = 32.0) } } } }
    assertEquals(
      listOf(BearingHapticNotch(360.0, 32.0, HapticFeedbackType.SegmentTick)),
      other.camera.settings.rotate.haptics,
    )
    assertEquals(
      emptyList(),
      MapInteractions(north) { camera { rotate { haptics {} } } }.camera.settings.rotate.haptics,
    )
  }

  @Test
  fun notches_lie_every_spacing_from_the_offset_within_one_turn() {
    assertEquals(listOf(2.0, 92.0, 182.0, 272.0), BearingHapticNotch(90.0, -358.0, Tick).bearings())
    assertEquals(listOf(0.0, 100.0, 200.0, 300.0), BearingHapticNotch(100.0, 0.0, Tick).bearings())
    for (spacing in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
      assertFailsWith<IllegalArgumentException> {
        MapInteractions { camera { rotate { haptics { notch(spacing) } } } }
      }
    }
    assertFailsWith<IllegalArgumentException> {
      MapInteractions { camera { rotate { haptics { notch(90.0, offset = Double.NaN) } } } }
    }
  }

  private companion object {
    val Tick = HapticFeedbackType.SegmentTick
  }
}
