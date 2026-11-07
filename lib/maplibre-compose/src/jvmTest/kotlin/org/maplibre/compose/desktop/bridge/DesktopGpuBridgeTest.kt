package org.maplibre.compose.desktop.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiFrameResult
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.compose.testing.runMapTest

class DesktopGpuBridgeTest {
  @Test
  fun a_replacement_target_becomes_queryable_after_its_first_frame() = runMapTest {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(background("#ff0000"))
      fixture.awaitUntil("the initial frame") {
        fixture.tryReadPixel(32, 32)?.isNear(RgbaPixel(255, 0, 0, 255)) == true
      }
      assertNotNull(fixture.session.readySession())
      fixture.renderingPaused = true
      fixture.extent = MapExtent.fromPhysical(191, 127, 1.0)
      assertEquals(MlnFfiFrameResult.AwaitUpdate, fixture.frame())
      assertNull(fixture.session.readySession(), "Replacement invalidates the rendered transform")
      fixture.renderingPaused = false
      fixture.awaitUntil("the replacement frame to become queryable") {
        fixture.session.readySession() != null
      }
      assertTrue(fixture.readPixel(32, 32).isNear(RgbaPixel(255, 0, 0, 255)))
    }
  }

  @Test
  fun map_updates_render_after_the_host_releases_the_sampled_frame() = runMapTest {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(background("#ff0000"))
      fixture.awaitUntil("the held red frame") {
        fixture.tryReadPixel(32, 32)?.isNear(RgbaPixel(255, 0, 0, 255)) == true
      }
      val session = assertNotNull(fixture.session.readySession())
      fixture.awaitUntil("the host to hold only the frame it shows") {
        session.getSnapshot().acquiredFrameCount == 1u
      }
      fixture.loadStyle(background("#0000ff"))
      fixture.awaitUntil("blue after releasing the red frame") {
        fixture.tryReadPixel(32, 32)?.isNear(RgbaPixel(0, 0, 255, 255)) == true
      }
      // A ring host releases the red frame at its next frame, once Compose has drawn blue.
      fixture.awaitUntil("the host to release the frame it no longer shows") {
        session.getSnapshot().acquiredFrameCount == 1u
      }
      fixture.loseSurface()
      assertTrue(session.isClosed || session.getSnapshot().acquiredFrameCount == 0u)
    }
  }

  @Test
  fun a_session_ring_renders_the_next_frame_while_the_host_holds_the_shown_one() = runMapTest {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(background("#ff0000"))
      fixture.awaitUntil("the red frame") {
        fixture.tryReadPixel(32, 32)?.isNear(RgbaPixel(255, 0, 0, 255)) == true
      }
      if (!fixture.sessionRingTarget) {
        FfiTestPlatform.skip("This backend renders into a texture the host lends")
      }
      val session = assertNotNull(fixture.session.readySession())
      fixture.awaitUntil("the host to hold only the red frame") {
        session.getSnapshot().acquiredFrameCount == 1u
      }
      fixture.renderingPaused = true
      fixture.loadStyle(background("#0000ff"))
      // Each frame keeps the shown frame acquired, and demands the next one into another texture.
      fixture.awaitUntil("a frame rendered while the red one stays acquired") {
        fixture.frame() is MlnFfiFrameResult.Rendered
      }
      assertEquals(2u, session.getSnapshot().acquiredFrameCount)
      fixture.renderingPaused = false
      fixture.awaitUntil("blue") {
        fixture.tryReadPixel(32, 32)?.isNear(RgbaPixel(0, 0, 255, 255)) == true
      }
      fixture.awaitUntil("the host to release the frames it no longer shows") {
        session.getSnapshot().acquiredFrameCount == 1u
      }
    }
  }

  @Test
  fun closing_another_map_keeps_the_remaining_map_able_to_resize_and_render() = runMapTest {
    val first = BridgeMapFixture.create()
    var firstClosed = false
    try {
      BridgeMapFixture.create().use { remaining ->
        first.loadStyle(background("#ff0000"))
        first.awaitUntil("the first map to render red") {
          first.tryReadPixel(32, 32)?.isNear(RgbaPixel(255, 0, 0, 255)) == true
        }
        remaining.loadStyle(background("#0000ff"))
        remaining.awaitUntil("the second map to render blue") {
          remaining.tryReadPixel(32, 32)?.isNear(RgbaPixel(0, 0, 255, 255)) == true
        }
        firstClosed = true
        first.closeNow()
        remaining.extent = MapExtent.fromPhysical(191, 127, 1.0)
        remaining.loadStyle(background("#00ff00"))
        remaining.awaitUntil("the remaining map to render after closing its neighbour") {
          remaining.tryReadPixel(32, 32)?.isNear(RgbaPixel(0, 255, 0, 255)) == true
        }
        assertTrue(remaining.readPixel(190, 126).isNear(RgbaPixel(0, 255, 0, 255)))
      }
    } finally {
      if (!firstClosed) first.close()
    }
  }

  private fun background(color: String) =
    BaseStyle.Json(
      """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"$color"}}]}""",
    )
}
