package org.maplibre.compose.map

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.sources.RasterSource
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.TileSetOptions
import org.maplibre.compose.style.OpRecordingStyleBinding

class StyleSourcesSnapshotTest {

  private fun testSource(id: String) =
    RasterSource(id, listOf("https://example.invalid/{z}/{x}/{y}.png"), TileSetOptions())

  @Test
  fun concurrent_refreshes_keep_both_added_sources() {
    val seenA = AtomicInteger(0)
    val startedARefresh = CountDownLatch(1)
    val releaseARefresh = CountDownLatch(1)
    val binding =
      object : OpRecordingStyleBinding() {
        override fun getSource(id: String): Source? {
          val source = super.getSource(id)
          if (id == "a" && seenA.incrementAndGet() >= 2) {
            startedARefresh.countDown()
            assertTrue(releaseARefresh.await(5, TimeUnit.SECONDS))
          }
          return source
        }
      }
    val state =
      MapState(
        cameraPosition = CameraPosition(),
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        logger = null,
        hostDispatcher = Dispatchers.Default,
      )
    val adapter = FakeMapAdapter()
    state.attachSession(adapter)
    state.callbacks.onStyleChanged(adapter, binding)

    val addA = Thread { runBlocking { state.sources.add(testSource("a")) } }
    addA.start()
    assertTrue(startedARefresh.await(5, TimeUnit.SECONDS))
    runBlocking { state.sources.add(testSource("b")) }
    releaseARefresh.countDown()
    addA.join(5_000)
    assertTrue(!addA.isAlive)

    assertEquals(setOf("a", "b"), state.sources.ids.toSet())
    state.close()
  }
}
