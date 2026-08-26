package org.maplibre.compose.style

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.sources.RasterSource
import org.maplibre.compose.sources.TileSetOptions

class StyleCompositionHostThreadingTest {

  /** Records the thread of each mutation and simulates the blocking owner-thread hop. */
  private class BlockingRecordingStyleBinding(private val mutationDelayMillis: Long) :
    OpRecordingStyleBinding() {
    val opThreads: MutableList<String> = mutableListOf()

    override fun op(name: String) {
      if (mutationDelayMillis > 0) Thread.sleep(mutationDelayMillis)
      synchronized(this) {
        super.op(name)
        opThreads.add(Thread.currentThread().name)
      }
    }

    fun opsSnapshot(): List<String> = synchronized(this) { ops.toList() }

    fun opThreadsSnapshot(): List<String> = synchronized(this) { opThreads.toList() }

    fun clear(): Unit =
      synchronized(this) {
        ops.clear()
        opThreads.clear()
      }
  }

  private fun testSource(id: String) =
    RasterSource(id, listOf("https://example.invalid/{z}/{x}/{y}.png"), TileSetOptions())

  @Test
  fun blocking_style_calls_stall_the_host_thread_but_not_the_caller() = runBlocking {
    val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "style-host") }
    val dispatcher = executor.asCoroutineDispatcher()
    val recording = BlockingRecordingStyleBinding(mutationDelayMillis = 150)
    val rootNode = StyleNode(recording, null)
    val host =
      StyleCompositionHost(
        dispatcher = dispatcher,
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        logger = null,
      )
    val a = testSource("a")
    val b = testSource("b")
    var showSecond by mutableStateOf(false)

    try {
      // The "UI" thread here is the test's main thread; setContent must not run the composition
      // or the slow applies on it.
      val start = System.nanoTime()
      host.setContent(rootNode) {
        RasterLayer(id = "layer-a", source = a, minZoom = 0f)
        if (showSecond) RasterLayer(id = "layer-b", source = b, minZoom = 0f)
      }
      val setContentMillis = (System.nanoTime() - start) / 1_000_000
      assertTrue(setContentMillis < 100, "setContent did not block: ${setContentMillis}ms")

      withTimeout(5_000) { while (recording.opsSnapshot().size < 2) delay(10) }
      assertEquals(listOf("addSource:a", "addLayer:layer-a"), recording.opsSnapshot())
      assertTrue(
        recording.opThreadsSnapshot().all { it.startsWith("style-host") },
        "initial applies ran on ${recording.opThreadsSnapshot()}",
      )
      recording.clear()

      // A recomposition's slow applies run on the host thread; the writer returns immediately.
      val writeStart = System.nanoTime()
      showSecond = true
      val writeMillis = (System.nanoTime() - writeStart) / 1_000_000
      assertTrue(writeMillis < 100, "state write did not block: ${writeMillis}ms")

      withTimeout(5_000) { while (recording.opsSnapshot().size < 2) delay(10) }
      assertEquals(listOf("addSource:b", "addLayerAbove:layer-b"), recording.opsSnapshot())
      assertTrue(
        recording.opThreadsSnapshot().all { it.startsWith("style-host") },
        "applies ran on ${recording.opThreadsSnapshot()}",
      )
    } finally {
      host.close()
      executor.shutdown()
    }
  }
}
