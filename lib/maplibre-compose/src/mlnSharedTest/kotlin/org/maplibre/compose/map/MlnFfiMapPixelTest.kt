package org.maplibre.compose.map

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.RgbaPixel

class MlnFfiMapPixelTest {

  @Test
  fun a_solid_background_reaches_the_render_target() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(STYLE)
      fixture.hasRendered = false
      fixture.pumpUntilRendered()

      val extent = BridgeMapFixture.DEFAULT_EXTENT
      val pixel = fixture.readPixel(extent.physicalWidth / 2, extent.physicalHeight / 2)
      assertNear(EXPECTED, pixel)
    }
  }

  private fun assertNear(expected: RgbaPixel, actual: RgbaPixel) {
    val differences =
      listOf(
        abs(expected.red - actual.red),
        abs(expected.green - actual.green),
        abs(expected.blue - actual.blue),
        abs(expected.alpha - actual.alpha),
      )
    assertTrue(
      differences.all { it <= CHANNEL_TOLERANCE },
      "Expected $expected within $CHANNEL_TOLERANCE per channel, got $actual",
    )
  }

  private companion object {
    const val CHANNEL_TOLERANCE = 2

    val EXPECTED = RgbaPixel(red = 0x33, green = 0x66, blue = 0x99, alpha = 0xff)

    val STYLE =
      BaseStyle.Json(
        """
        {"version":8,"sources":{},"layers":[
          {"id":"background","type":"background","paint":{"background-color":"#336699"}}
        ]}
        """
      )
  }
}
