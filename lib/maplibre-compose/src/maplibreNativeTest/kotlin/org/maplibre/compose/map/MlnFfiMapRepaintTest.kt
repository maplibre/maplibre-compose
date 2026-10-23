package org.maplibre.compose.map

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.layers.asLayerProperty
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.LayerPropertyKind
import org.maplibre.compose.style.install
import org.maplibre.compose.style.uninstall
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.compose.testing.runMapTest

class MlnFfiMapRepaintTest {
  @Test
  fun style_mutations_change_pixels_without_unconditional_rendering() = runMapTest {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(background("#0000ff"))
      fixture.awaitUntil("the initial blue background") {
        fixture.tryReadPixel(256, 256)?.isNear(RgbaPixel(0, 0, 255, 255)) == true
      }
      fixture.assertRedrawsAs("the replaced base style", RgbaPixel(255, 0, 0, 255)) {
        fixture.session.setBaseStyle(background("#ff0000"))
      }
      val style = checkNotNull(fixture.style)
      val layer =
        TestLayer("green", "background").apply {
          paint("background-color", (const(Color.Green)).asLayerProperty())
        }
      fixture.assertRedrawsAs("the installed layer", RgbaPixel(0, 255, 0, 255)) {
        style.install(layer)
      }
      fixture.assertRedrawsAs("the uninstalled layer", RgbaPixel(255, 0, 0, 255)) {
        style.uninstall(layer)
      }
      assertEquals(emptyList(), fixture.errors)
    }
  }

  @Test
  fun a_paint_change_at_idle_renders_exactly_once() = runMapTest {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(
        BaseStyle.Json(
          """{"version":8,"transition":{"duration":0,"delay":0},"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#0000ff"}}]}""",
        ),
      )
      fixture.awaitRendered()
      fixture.awaitSettled()
      val style = checkNotNull(fixture.style)
      style.setLayerProperty(
        "background",
        "background-color",
        JsonPrimitive("#00ff00"),
        LayerPropertyKind.Paint,
      )
      assertEquals(1L, fixture.countFramesOver(1.seconds))
      assertTrue(fixture.readPixel(256, 256).isNear(RgbaPixel(0, 255, 0, 255)))
      assertEquals(emptyList(), fixture.errors)
    }
  }

  @Test
  fun a_paint_transition_renders_until_it_finishes_then_settles() = runMapTest {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(background("#0000ff"))
      fixture.awaitRendered()
      fixture.awaitSettled()
      val style = checkNotNull(fixture.style)
      style.setLayerProperty(
        "background",
        "background-color",
        JsonPrimitive("#00ff00"),
        LayerPropertyKind.Paint,
      )
      fixture.awaitUntil("the paint transition to finish", 5.seconds) {
        fixture.readPixel(256, 256).isNear(RgbaPixel(0, 255, 0, 255))
      }
      fixture.awaitSettled()
      assertEquals(0L, fixture.countFramesOver(300.milliseconds))
      assertEquals(emptyList(), fixture.errors)
    }
  }

  /** Waits for the map to settle, applies [mutate], and waits for a requested frame to show it. */
  private suspend fun BridgeMapFixture.assertRedrawsAs(
    change: String,
    expected: RgbaPixel,
    mutate: suspend () -> Unit,
  ) {
    awaitSettled()
    val framesBefore = renderedFrames
    mutate()
    var pixel: RgbaPixel? = null
    try {
      awaitUntil("a frame to show $change as $expected") {
        pixel = tryReadPixel(256, 256)
        pixel?.isNear(expected) == true
      }
    } catch (error: IllegalStateException) {
      throw IllegalStateException(
        "${error.message} The last pixel was $pixel, after " +
          "${renderedFrames - framesBefore} frames since the change.",
        error,
      )
    }
  }

  private fun background(color: String) =
    BaseStyle.Json(
      """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"$color"}}]}""",
    )
}
