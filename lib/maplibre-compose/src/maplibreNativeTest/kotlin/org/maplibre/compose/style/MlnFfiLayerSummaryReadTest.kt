@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.maplibre.compose.style

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.mlnffi.MlnFfiOwnerThread
import org.maplibre.compose.mlnffi.parkForTest

class MlnFfiLayerSummaryReadTest {

  /**
   * Reading a large style's layer metadata must not hold the map owner thread for the whole read.
   * While it does, nothing else reaches that thread and native render feedback is never drained, so
   * a transition that started before the read does not advance and the map jumps to its end state.
   */
  @Test
  fun reading_a_large_style_releases_the_owner_thread() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(largeStyle(LAYER_COUNT))
      fixture.pumpUntilRendered()
      fixture.settle()
      val style = assertNotNull(fixture.style as? MlnFfiStyleBinding, "Errors: ${fixture.errors}")

      // How long a host takes over the read is its own business, so the same read runs twice on it:
      // once yielding after every layer, and once held in a single owner-thread call the way it was
      // read before. A fast host fits every layer of the production slice into one call.
      val sliced = readWhileProbing(style) { style.layerSummaries(Duration.ZERO) }
      val whole = readWhileProbing(style) { style.layerSummaries(Duration.INFINITE) }

      assertEquals(LAYER_COUNT, sliced.summaries.size)
      assertEquals(LAYER_COUNT, whole.summaries.size)
      assertTrue(
        sliced.probes > whole.probes,
        "reading $LAYER_COUNT layers one per owner-thread call let ${sliced.probes} other " +
          "owner-thread calls through, no more than the ${whole.probes} of the single call: the " +
          "sliced read did not yield",
      )
    }
  }

  private class Read(val summaries: Map<String, LayerSummary>, val probes: Int)

  /**
   * Runs [read] while another thread asks the owner thread for something small, and counts the
   * calls that finished before the read did. The loop drains native events after every owner-thread
   * call, so a read that lets other calls through is a read that lets render feedback through.
   */
  private fun readWhileProbing(
    style: MlnFfiStyleBinding,
    read: () -> Map<String, LayerSummary>,
  ): Read {
    val probing = AtomicBoolean(true)
    val probes = AtomicInt(0)
    val prober =
      MlnFfiOwnerThread("maplibre-compose-test-prober") {
        while (probing.load()) {
          style.layerExists("layer-0")
          probes.addAndFetch(1)
        }
      }
    prober.start()
    // The probe proves nothing until it is running, so the read waits for its first call.
    while (probes.load() == 0) parkForTest(1L)
    val before = probes.load()
    val summaries = read()
    val during = probes.load() - before
    probing.store(false)
    check(prober.join(PROBE_STOP_TIMEOUT_MILLIS)) { "the probe thread did not stop" }
    return Read(summaries, during)
  }

  private fun largeStyle(layers: Int): BaseStyle {
    val entries =
      (0 until layers).joinToString(",") { index ->
        """{"id":"layer-$index","type":"background","paint":{"background-color":"#0000ff"}}"""
      }
    return BaseStyle.Json("""{"version":8,"sources":{},"layers":[$entries]}""")
  }

  private companion object {
    /**
     * Enough owner-thread calls in the yielding read for the probe to land between some of them.
     */
    const val LAYER_COUNT = 600

    const val PROBE_STOP_TIMEOUT_MILLIS = 30_000L
  }
}
