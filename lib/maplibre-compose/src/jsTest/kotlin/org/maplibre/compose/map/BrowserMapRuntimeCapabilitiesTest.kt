package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.offline.OfflinePackDefinition
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.BoundingBox

class BrowserMapRuntimeCapabilitiesTest {
  @Test
  fun web_reports_unsupported_offline_features_without_disabling_runtime_children() = runTest {
    val runtime = createMapRuntime(MapRuntimeOptions())
    val manager = runtime.offlineManager

    assertFalse(runtime.capabilities.supportsOfflinePacks)
    assertFalse(runtime.capabilities.supportsAmbientCacheManagement)
    assertTrue(manager.packs.isEmpty())
    assertFailsWith<UnsupportedOperationException> {
      manager.create(
        OfflinePackDefinition.TilePyramid(
          styleUrl = "https://example.test/style.json",
          bounds = BoundingBox(west = -1.0, south = -1.0, east = 1.0, north = 1.0),
          pixelRatio = 1f,
        )
      )
    }
    assertFailsWith<UnsupportedOperationException> { manager.clearAmbientCache() }

    val state = runtime.createMapState()
    val snapshotter = runtime.createSnapshotter(BaseStyle.Empty)
    assertFalse(state.isClosed)
    assertEquals(BaseStyle.Empty, snapshotter.style.baseStyle)

    runtime.close()
    runtime.awaitClosed()
  }
}
