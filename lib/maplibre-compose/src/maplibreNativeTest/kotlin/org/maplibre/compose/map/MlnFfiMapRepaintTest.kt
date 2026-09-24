package org.maplibre.compose.map

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.layers.asLayerProperty
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.mlnffi.MlnFfiFrameResult
import org.maplibre.compose.mlnffi.MlnFfiGate
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.LayerPropertyKind
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
      val layer =
        TestLayer("green", "background").apply {
          paint("background-color", (const(Color.Green)).asLayerProperty())
        }
      fixture.assertRedrawsAs(RgbaPixel(0, 255, 0, 255)) { style.install(layer) }
      fixture.assertRedrawsAs(RgbaPixel(255, 0, 0, 255)) { style.uninstall(layer) }
      assertEquals(emptyList(), fixture.errors)
    }
  }

  @Test
  fun a_paint_change_at_idle_renders_exactly_once() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(
        BaseStyle.Json(
          """{"version":8,"transition":{"duration":0,"delay":0},"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#0000ff"}}]}"""
        )
      )
      fixture.pumpUntilRendered()
      fixture.settle()
      checkNotNull(fixture.style)
        .setLayerProperty(
          "background",
          "background-color",
          JsonPrimitive("#00ff00"),
          LayerPropertyKind.PAINT,
        )
      assertEquals(1, fixture.renderOnDemand(1.seconds))
      assertTrue(fixture.readPixel(256, 256).isNear(RgbaPixel(0, 255, 0, 255)))
      assertEquals(emptyList(), fixture.errors)
    }
  }

  @Test
  fun a_paint_transition_waits_for_fresh_native_updates_then_finishes_and_settles() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(background("#0000ff"))
      fixture.pumpUntilRendered()
      fixture.settle()
      val style = checkNotNull(fixture.style)
      style.setLayerProperty(
        "background",
        "background-color",
        JsonPrimitive("#00ff00"),
        LayerPropertyKind.PAINT,
      )
      val ownerEntered = MlnFfiGate()
      val releaseOwner = MlnFfiGate()
      try {
        assertTrue(
          fixture.session.postOwnerTaskForTest {
            ownerEntered.open()
            releaseOwner.awaitUntilOpen()
          }
        )
        assertTrue(ownerEntered.await(5_000), "The owner did not reach the gate")
        // Allow one draw of the published update while its native transition clock is held.
        fixture.session.onSurfaceChanged(BridgeMapFixture.DEFAULT_EXTENT)
        assertTrue(fixture.frame() is MlnFfiFrameResult.Rendered)
        assertTrue(
          fixture.renderOnDemand(300.milliseconds) <= 1,
          "Rendering kept requesting frames while the native update could not advance",
        )
      } finally {
        releaseOwner.open()
      }
      val deadline = TimeSource.Monotonic.markNow() + 5.seconds
      while (!fixture.readPixel(256, 256).isNear(RgbaPixel(0, 255, 0, 255))) {
        check(deadline.hasNotPassedNow()) { "The paint transition did not finish" }
        fixture.renderOnDemand(20.milliseconds)
      }
      fixture.settle()
      assertEquals(0, fixture.renderOnDemand(300.milliseconds))
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
