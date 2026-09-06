package org.maplibre.compose.map

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.style.BaseStyle

class SnapshotCompositionTest {
  @Test
  fun snapshot_content_reads_the_viewport_of_its_own_request() = runTest {
    val sizes = mutableListOf<DpSize?>()
    val runtime = mapRuntimeForTest(createSnapshotterAdapter = { FakeSnapshotterAdapter() })
    val snapshotter =
      runtime.createSnapshotter(BaseStyle.Empty) { sizes += LocalViewport.current?.size }

    snapshotter.capture(MapSnapshotRequest(width = 30, height = 20))

    assertEquals(setOf(DpSize(30.dp, 20.dp)), sizes.toSet())
    runtime.close()
    runtime.awaitClosed()
  }

  @Test
  fun snapshot_content_has_no_map_state() = runTest {
    val states = mutableListOf<MapState?>()
    val runtime = mapRuntimeForTest(createSnapshotterAdapter = { FakeSnapshotterAdapter() })
    val snapshotter = runtime.createSnapshotter(BaseStyle.Empty) { states += LocalMapState.current }

    snapshotter.capture(MapSnapshotRequest(1, 1))

    assertEquals(listOf<MapState?>(null), states)
    runtime.close()
    runtime.awaitClosed()
  }
}
