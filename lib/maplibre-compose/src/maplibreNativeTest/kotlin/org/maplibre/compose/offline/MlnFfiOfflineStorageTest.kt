package org.maplibre.compose.offline

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntime
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.TestMapMain
import org.maplibre.compose.testing.runMapTest
import org.maplibre.nativeffi.generated.OfflineRegionStatus
import org.maplibre.nativeffi.generated.RuntimeEvent
import org.maplibre.nativeffi.generated.RuntimeEventOfflineRegionStatus
import org.maplibre.nativeffi.generated.RuntimeEventPayload
import org.maplibre.nativeffi.generated.RuntimeEventType
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

/** Exercises the application cache's offline storage without a UI. */
class MlnFfiOfflineStorageTest {

  private val cacheFile = FfiTestPlatform.createCacheFile()

  private val options =
    MlnFfiRuntimeOptions(
      cacheFile = cacheFile,
      maximumCacheSizeBytes = null,
      mainDispatcher = TestMapMain.dispatcher,
    )

  @AfterTest
  fun cleanUp() {
    FfiTestPlatform.deleteCacheFile(cacheFile)
  }

  @Test
  fun a_status_query_does_not_overwrite_progress_published_while_it_waited() = runMapTest {
    val owner = MlnFfiRuntime(options)
    val storage = MlnFfiOfflineStorage(owner)
    owner.start()
    try {
      storage.awaitReady()
      val pack =
        storage.create(
          OfflinePackDefinition.TilePyramid(
            styleUrl = "https://example.com/style.json",
            bounds = BoundingBox(Position(0.0, 0.0), Position(0.25, 0.25)),
            pixelRatio = 1f,
            minZoom = 0.0,
            maxZoom = 1.0,
          ),
          ByteArray(0),
        )
      val held = CompletableDeferred<OfflineRegionStatus>()
      val query =
        async(start = CoroutineStart.UNDISPATCHED) {
          storage.refreshStatus(pack) { held }
        }
      assertFalse(query.isCompleted)
      val newer = OfflineRegionStatus(completedResourceCount = 7uL)
      owner.onRuntimeEvent(
        RuntimeEvent(
          type = RuntimeEventType.OFFLINE_REGION_STATUS_CHANGED,
          payload =
            RuntimeEventPayload.OfflineRegionStatus(
              RuntimeEventOfflineRegionStatus(pack.regionId, newer),
            ),
        ),
      )
      assertEquals(newer.toDownloadProgress(), pack.downloadProgress.value)

      held.complete(OfflineRegionStatus(completedResourceCount = 2uL))
      query.await()

      assertEquals(newer.toDownloadProgress(), pack.downloadProgress.value)
    } finally {
      storage.close()
      owner.close()
      owner.awaitClosed()
    }
  }

  @Test
  fun a_create_whose_caller_stops_waiting_still_publishes_its_pack() = runMapTest {
    val owner = MlnFfiRuntime(options)
    val storage = MlnFfiOfflineStorage(owner)
    owner.start()
    try {
      storage.awaitReady()
      // Undispatched, so native accepts the create before the caller is cancelled.
      val caller =
        launch(start = CoroutineStart.UNDISPATCHED) {
          storage.create(
            OfflinePackDefinition.TilePyramid(
              styleUrl = "https://example.com/style.json",
              bounds = BoundingBox(Position(0.0, 0.0), Position(0.25, 0.25)),
              pixelRatio = 1f,
              minZoom = 0.0,
              maxZoom = 1.0,
            ),
            ByteArray(0),
          )
        }
      caller.cancel()
      withTimeout(10.seconds) {
        storage.state.first { (it as? OfflineStorageState.Ready)?.packs?.size == 1 }
      }
    } finally {
      storage.close()
      owner.close()
      owner.awaitClosed()
    }
  }

  @Test
  fun an_initial_cache_budget_failure_is_published_and_rejects_operations() = runMapTest {
    val owner = MlnFfiRuntime(options.copy(maximumCacheSizeBytes = -1))
    val storage = MlnFfiOfflineStorage(owner)
    owner.start()
    try {
      assertFailsWith<OfflineStorageException> { storage.clearAmbientCache() }
      assertIs<OfflineStorageState.Failed>(storage.state.value)
    } finally {
      storage.close()
      owner.close()
      owner.awaitClosed()
    }
  }
}
