package org.maplibre.compose.desktop.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.mlnffi.MlnFfiFrameResult
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.RgbaPixel

class DesktopGpuBridgeTest {
  @Test
  fun a_replacement_target_becomes_queryable_after_its_first_frame() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(background("#ff0000"))
      fixture.pumpUntil("the initial frame") {
        fixture.tryReadPixel(32, 32)?.isNear(RgbaPixel(255, 0, 0, 255)) == true
      }
      assertNotNull(fixture.session.readySession())
      val resized = MapExtent.fromPhysical(191, 127, 1.0)
      assertEquals(MlnFfiFrameResult.AwaitUpdate, fixture.frame(resized))
      assertNull(fixture.session.readySession(), "Replacement invalidates the rendered transform")
      fixture.pumpUntil("the replacement frame to become queryable", extent = resized) {
        fixture.session.readySession() != null
      }
      assertTrue(fixture.readPixel(32, 32).isNear(RgbaPixel(255, 0, 0, 255)))
    }
  }

  @Test
  fun map_updates_render_after_the_host_releases_the_sampled_frame() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(background("#ff0000"))
      fixture.pumpUntil("the held red frame") {
        fixture.tryReadPixel(32, 32)?.isNear(RgbaPixel(255, 0, 0, 255)) == true
      }
      val session = assertNotNull(fixture.session.readySession())
      assertEquals(1u, session.getSnapshot().acquiredFrameCount)
      fixture.loadStyle(background("#0000ff"))
      fixture.pumpUntil("blue after releasing the red frame") {
        fixture.tryReadPixel(32, 32)?.isNear(RgbaPixel(0, 0, 255, 255)) == true
      }
      assertEquals(1u, session.getSnapshot().acquiredFrameCount)
      fixture.loseSurface()
      assertTrue(session.isClosed || session.getSnapshot().acquiredFrameCount == 0u)
    }
  }

  @Test
  fun closing_another_map_keeps_the_remaining_map_able_to_resize_and_render() {
    val first = BridgeMapFixture.create()
    var firstClosed = false
    try {
      BridgeMapFixture.create().use { remaining ->
        first.loadStyle(background("#ff0000"))
        first.pumpUntil("the first map to render red") {
          first.tryReadPixel(32, 32)?.isNear(RgbaPixel(255, 0, 0, 255)) == true
        }
        remaining.loadStyle(background("#0000ff"))
        remaining.pumpUntil("the second map to render blue") {
          remaining.tryReadPixel(32, 32)?.isNear(RgbaPixel(0, 0, 255, 255)) == true
        }
        first.close()
        firstClosed = true
        val resized = MapExtent.fromPhysical(191, 127, 1.0)
        remaining.loadStyle(background("#00ff00"), extent = resized)
        remaining.pumpUntil(
          "the remaining map to render after closing its neighbour",
          extent = resized,
        ) {
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
