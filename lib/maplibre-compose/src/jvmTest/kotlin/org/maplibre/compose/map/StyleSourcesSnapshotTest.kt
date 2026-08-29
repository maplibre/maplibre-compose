package org.maplibre.compose.map

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.sources.RasterSource
import org.maplibre.compose.sources.TileSetOptions
import org.maplibre.compose.style.OpRecordingStyleBinding

class StyleSourcesSnapshotTest {

  private fun testSource(id: String) =
    RasterSource(id, listOf("https://example.invalid/{z}/{x}/{y}.png"), TileSetOptions())

  @Test
  fun concurrent_adds_keep_both_added_sources() = runBlocking {
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
    state.callbacks.onStyleChanged(adapter, OpRecordingStyleBinding())

    val addA = async { state.sources.add(testSource("a")) }
    val addB = async { state.sources.add(testSource("b")) }
    addA.await()
    addB.await()

    assertEquals(setOf("a", "b"), state.sources.ids.toSet())
    state.close()
  }
}
