@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.mlnffi.BridgeMapFixture

class MlnFfiLayerSummaryReadTest {

  /**
   * Reading a large style's layer metadata must not hold the map owner thread for the whole read.
   * While it does, native render feedback is never drained, so a transition that started before the
   * read does not advance and the map jumps to its end state.
   */
  @Test
  fun reading_a_large_style_lets_native_render_feedback_through() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(largeStyle(LAYER_COUNT))
      fixture.pumpUntilRendered()
      fixture.settle()
      val style = assertNotNull(fixture.style)

      // A transition keeps native producing updates for the render thread to consume.
      style.setLayerProperty(
        layerId = "layer-0",
        name = "background-color",
        value = JsonPrimitive("#00ff00"),
        kind = LayerPropertyKind.PAINT,
      )

      val before = fixture.renderedFrames.load()
      val summaries = fixture.whileRenderingOnRendererThread { style.layerSummaries() }
      val rendered = fixture.renderedFrames.load() - before

      assertEquals(LAYER_COUNT, summaries.size)
      // A frame renders only after the owner thread drains a native update, so frames during the
      // read count the times it yielded. Reading every layer in one call leaves the request made
      // before the read and the one made after it, and nothing in between.
      assertTrue(
        rendered >= MIN_YIELDED_FRAMES,
        "only $rendered frames rendered while reading $LAYER_COUNT layers: the read did not yield",
      )
    }
  }

  private fun largeStyle(layers: Int): BaseStyle {
    val entries =
      (0 until layers).joinToString(",") { index ->
        """{"id":"layer-$index","type":"background","paint":{"background-color":"#0000ff"}}"""
      }
    return BaseStyle.Json("""{"version":8,"sources":{},"layers":[$entries]}""")
  }

  private companion object {
    /** Enough layers that one owner-thread time slice cannot read them all. */
    const val LAYER_COUNT = 600

    /** Measured: about 7 frames while yielding, and 2 without. */
    const val MIN_YIELDED_FRAMES = 4
  }
}
