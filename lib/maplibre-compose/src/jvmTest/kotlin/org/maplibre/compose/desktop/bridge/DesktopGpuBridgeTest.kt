package org.maplibre.compose.desktop.bridge

import kotlin.test.Test
import kotlin.test.assertTrue
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.RgbaPixel

class DesktopGpuBridgeTest {
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
      """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"$color"}}]}"""
    )
}
