package org.maplibre.compose.mlnffi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.maplibre.nativeffi.camera.CameraOptions
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.map.MapOptions
import org.maplibre.nativeffi.runtime.RuntimeEventType
import org.maplibre.nativeffi.runtime.RuntimeHandle
import org.maplibre.nativeffi.runtime.RuntimeOptions

/** Direct FFI premise behind the shared runtime implementation; rendering is tested separately. */
class SharedNativeRuntimeProbeTest {
  @Test
  fun two_maps_share_an_owner_runtime_and_close_independently() = runBlocking {
    FfiTestPlatform.initialize()
    val cacheFile = FfiTestPlatform.createCacheFile()
    val done = CompletableDeferred<Result<Unit>>()
    val thread =
      MlnFfiOwnerThread("shared-runtime-probe") {
        done.complete(
          runCatching {
            RuntimeHandle.create(RuntimeOptions().also { it.cachePath = cacheFile.toString() })
              .use { runtime ->
                MapHandle.create(runtime, MapOptions()).use { first ->
                  MapHandle.create(runtime, MapOptions()).use { second ->
                    runtime.drainEvents()
                    first.jumpTo(CameraOptions().also { it.zoom = 2.0 })
                    second.jumpTo(CameraOptions().also { it.zoom = 4.0 })
                    val events = runtime.drainEvents().events
                    val cameraEvents = events.filter {
                      it.type == RuntimeEventType.MAP_CAMERA_DID_CHANGE
                    }
                    assertTrue(cameraEvents.any { it.mapSource === first })
                    assertTrue(cameraEvents.any { it.mapSource === second })
                    assertEquals(2.0, first.camera.zoom)
                    assertEquals(4.0, second.camera.zoom)

                    first.close()
                    assertFalse(second.isClosed)
                    second.jumpTo(CameraOptions().also { it.zoom = 6.0 })
                    assertEquals(6.0, second.camera.zoom)
                    assertTrue(
                      runtime.drainEvents().events.any {
                        it.type == RuntimeEventType.MAP_CAMERA_DID_CHANGE && it.mapSource === second
                      }
                    )
                  }
                }
              }
          }
        )
      }
    try {
      thread.start()
      withTimeout(10_000) { done.await() }.getOrThrow()
    } finally {
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
  }
}
