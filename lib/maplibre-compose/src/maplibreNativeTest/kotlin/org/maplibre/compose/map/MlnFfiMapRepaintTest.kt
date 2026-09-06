package org.maplibre.compose.map

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.install
import org.maplibre.compose.style.uninstall
import org.maplibre.compose.testing.RgbaPixel

class MlnFfiMapRepaintTest {
  @Test
  fun style_mutations_change_pixels_without_unconditional_rendering() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(background("#0000ff"))
      fixture.pumpUntil("the initial blue background") {
        fixture.tryReadPixel(256, 256)?.isNear(RgbaPixel(0, 0, 255, 255)) == true
      }
      fixture.assertRedrawsAs(RgbaPixel(255, 0, 0, 255)) {
        fixture.session.setBaseStyle(background("#ff0000"))
      }
      val style = checkNotNull(fixture.style)
      val layer = BackgroundLayer("green").apply { setBackgroundColor(const(Color.Green)) }
      fixture.assertRedrawsAs(RgbaPixel(0, 255, 0, 255)) { style.install(layer) }
      fixture.assertRedrawsAs(RgbaPixel(255, 0, 0, 255)) { style.uninstall(layer) }
      assertEquals(emptyList(), fixture.errors)
    }
  }

  private fun BridgeMapFixture.assertRedrawsAs(expected: RgbaPixel, mutate: () -> Unit) {
    settle()
    mutate()
    val deadline = TimeSource.Monotonic.markNow() + 30.seconds
    do {
      check(deadline.hasNotPassedNow()) {
        "No requested frame displayed $expected. Errors: $errors"
      }
      renderOnDemand(50.milliseconds)
    } while (!readPixel(256, 256).isNear(expected))
  }

  private fun background(color: String) =
    BaseStyle.Json(
      """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"$color"}}]}"""
    )
}
