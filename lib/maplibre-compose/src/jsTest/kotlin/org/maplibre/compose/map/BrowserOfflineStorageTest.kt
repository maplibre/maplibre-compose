package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.offline.OfflinePackDefinition
import org.maplibre.compose.offline.OfflineStorageState
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.BoundingBox

class BrowserOfflineStorageTest {
  @Test
  fun web_rejects_offline_operations_without_disabling_runtime_children() = runTest {
    val runtime = createMapRuntime(MapRuntimeOptions())
    val storage = runtime.offlineStorage

    assertTrue((storage.state.value as OfflineStorageState.Ready).packs.isEmpty())
    assertFailsWith<UnsupportedOperationException> {
      storage.create(
        OfflinePackDefinition.TilePyramid(
          styleUrl = "https://example.test/style.json",
          bounds = BoundingBox(west = -1.0, south = -1.0, east = 1.0, north = 1.0),
          pixelRatio = 1.0,
        )
      )
    }
    assertFailsWith<UnsupportedOperationException> { storage.clearAmbientCache() }

    val state = runtime.createMapState(BaseStyle.Demo)
    val snapshotter = runtime.createSnapshotter(BaseStyle.Empty)
    assertFalse(state.isClosed)
    assertEquals(BaseStyle.Empty, snapshotter.style.baseStyle)

    runtime.close()
    runtime.awaitClosed()
  }
}
