@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.TimeSource
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
      val style = assertNotNull(fixture.style as? MlnFfiStyleBinding, "Errors: ${fixture.errors}")

      // How many frames a host presents while reading depends on how fast it reads and renders, so
      // the same read runs twice on it: once in slices, and once held in a single owner-thread call
      // the way it was read before slicing.
      val sliced = fixture.readWhileRendering(style) { style.layerSummaries() }
      val whole = fixture.readWhileRendering(style) { style.layerSummaries(Duration.INFINITE) }

      assertEquals(LAYER_COUNT, sliced.summaries.size)
      assertEquals(LAYER_COUNT, whole.summaries.size)

      // A host that reads every layer within one slice has nothing to yield between calls, and the
      // two reads are then the same read.
      if (whole.elapsed < LAYER_READ_SLICE * MIN_SLICES) return

      // A frame renders only after the owner thread drains a native update, so frames during a read
      // count the times it yielded. The single call leaves the request made before the read and the
      // one made after it, and nothing in between.
      assertTrue(
        sliced.frames > whole.frames,
        "reading $LAYER_COUNT layers in $LAYER_READ_SLICE slices rendered ${sliced.frames} " +
          "frames in ${sliced.elapsed}, no more than the ${whole.frames} frames of the single " +
          "call in ${whole.elapsed}: the sliced read did not yield",
      )
    }
  }

  private class Read(
    val summaries: Map<String, LayerSummary>,
    val frames: Long,
    val elapsed: Duration,
  )

  /** Reads with a renderer thread presenting frames, while a transition feeds it native updates. */
  private fun BridgeMapFixture.readWhileRendering(
    style: MlnFfiStyleBinding,
    read: () -> Map<String, LayerSummary>,
  ): Read {
    style.setLayerProperty(
      layerId = "layer-0",
      name = "background-color",
      value = JsonPrimitive(nextColor()),
      kind = LayerPropertyKind.PAINT,
    )
    val framesBefore = renderedFrames.load()
    val started = TimeSource.Monotonic.markNow()
    val summaries = whileRenderingOnRendererThread(read)
    return Read(summaries, renderedFrames.load() - framesBefore, started.elapsedNow())
  }

  private var color = 0

  /** A new color each read, so each one runs against a transition of its own. */
  private fun nextColor(): String = COLORS[color++ % COLORS.size]

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

    /** Slices a read must span before the frames rendered during it say anything. */
    const val MIN_SLICES = 4

    val COLORS = listOf("#00ff00", "#ff0000", "#ffff00")
  }
}
