package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.mlnffi.MlnFfiMapExtent
import org.maplibre.compose.style.BaseStyle

/**
 * A resize must retarget the live session (maplibre-native-ffi #485) rather than re-attach, which
 * would discard the renderer's tile pyramid, atlases, and placement on every frame of a drag.
 *
 * Asserted on attach and retarget counts, since both paths render the same scene: a regression here
 * only stutters.
 */
class MlnFfiMapResizeTest {

  @Test
  fun resizing_retargets_the_live_session_rather_than_re_attaching() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      fixture.loadStyle(BaseStyle.Json(EMPTY_STYLE))
      fixture.pumpUntilRendered()

      val attachesAfterFirstFrame = fixture.session.attachCount
      assertEquals(0, fixture.session.retargetCount, "nothing to retarget before the first resize")

      // Rendering again at the new size is the real assertion; the counters only say which path got
      // there.
      fixture.hasRendered = false
      fixture.pumpUntil("the resized map to render", extent = WIDER_EXTENT) { fixture.hasRendered }

      assertEquals(
        attachesAfterFirstFrame,
        fixture.session.attachCount,
        "a resize at an unchanged scale factor must not attach a second session",
      )
      assertTrue(fixture.session.retargetCount > 0, "the resize should have retargeted")
    }
  }

  @Test
  fun a_resize_back_and_forth_keeps_reusing_the_one_session() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      fixture.loadStyle(BaseStyle.Json(EMPTY_STYLE))
      fixture.pumpUntilRendered()
      val attaches = fixture.session.attachCount

      // Every step is a new host target: a borrowed texture cannot be resized, only reallocated.
      listOf(WIDER_EXTENT, TALLER_EXTENT, BridgeMapFixture.DEFAULT_EXTENT).forEach { extent ->
        fixture.frame(extent)
        fixture.frame(extent)
      }

      assertEquals(attaches, fixture.session.attachCount, "no resize should have re-attached")
      assertTrue(
        fixture.session.retargetCount >= 3,
        "each of the three sizes should have retargeted, got ${fixture.session.retargetCount}",
      )
    }
  }

  private companion object {
    /** No sources or layers, so nothing here is waiting on the network. */
    const val EMPTY_STYLE: String =
      """{"version":8,"sources":{},"layers":[],"name":"resize-test"}"""

    val WIDER_EXTENT: MlnFfiMapExtent =
      MlnFfiMapExtent.fromLogical(width = 640, height = 512, scaleFactor = 1.0)

    val TALLER_EXTENT: MlnFfiMapExtent =
      MlnFfiMapExtent.fromLogical(width = 640, height = 600, scaleFactor = 1.0)
  }
}
