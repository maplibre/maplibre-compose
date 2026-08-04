package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.compose.desktop.DesktopMapExtent
import org.maplibre.compose.desktop.HeadlessMapFixture
import org.maplibre.compose.style.BaseStyle

/**
 * What a resize costs.
 *
 * Since maplibre-native-ffi #485 a live borrowed-texture session can be handed a replacement
 * texture, keeping its renderer and with it the tile pyramid, the glyph and image atlases, symbol
 * placement, and renderer-held feature state. Before that, following the host's new target meant
 * closing the session and attaching a new one, which threw all of it away and refetched — on every
 * frame of a drag-resize.
 *
 * Both paths render the same scene at the same size, so the difference is invisible from the
 * outside. These assert on the session's own attach and retarget counts instead, because a
 * regression here is silent: the map would still look correct and merely stutter.
 */
class DesktopMapResizeTest {

  @Test
  fun `resizing retargets the live session rather than re-attaching`() {
    val fixture = HeadlessMapFixture.create()
    fixture.use {
      fixture.loadStyle(BaseStyle.Json(EMPTY_STYLE))
      fixture.pumpUntilRendered()

      val attachesAfterFirstFrame = fixture.session.attachCount
      assertEquals(0, fixture.session.retargetCount, "nothing to retarget before the first resize")

      // Rendering again at the new size is the real assertion; the counters only say which path
      // got there. A retarget that silently left the session pointing at the old texture would
      // still bump the counter.
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
  fun `a resize back and forth keeps reusing the one session`() {
    val fixture = HeadlessMapFixture.create()
    fixture.use {
      fixture.loadStyle(BaseStyle.Json(EMPTY_STYLE))
      fixture.pumpUntilRendered()
      val attaches = fixture.session.attachCount

      // Every step is a new host target, which is what a drag-resize looks like: the host cannot
      // resize a borrowed texture, so it reallocates and bumps its generation each time.
      listOf(WIDER_EXTENT, TALLER_EXTENT, HeadlessMapFixture.DEFAULT_EXTENT).forEach { extent ->
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

  @Test
  fun `a scale factor change attaches a new session, because a renderer is built for one`() {
    val fixture = HeadlessMapFixture.create()
    fixture.use {
      fixture.loadStyle(BaseStyle.Json(EMPTY_STYLE))
      fixture.pumpUntilRendered()
      val attaches = fixture.session.attachCount

      // A renderer compiles its shaders for one pixel ratio and MapHandle's is fixed at creation,
      // so this is the one case that must not be followed in place. It is what moving a window
      // between displays of different density does.
      fixture.pumpUntil("the map to render at 2x", extent = HeadlessMapFixture.RETINA_EXTENT) {
        fixture.session.attachCount > attaches
      }

      assertTrue(
        fixture.session.attachCount > attaches,
        "a scale factor change must attach rather than retarget",
      )
    }
  }

  private companion object {
    /** No sources or layers, so nothing here is waiting on the network. */
    const val EMPTY_STYLE: String =
      """{"version":8,"sources":{},"layers":[],"name":"resize-test"}"""

    val WIDER_EXTENT: DesktopMapExtent =
      DesktopMapExtent.fromLogical(width = 640, height = 512, scaleFactor = 1.0)

    val TALLER_EXTENT: DesktopMapExtent =
      DesktopMapExtent.fromLogical(width = 640, height = 600, scaleFactor = 1.0)
  }
}
