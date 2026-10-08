@file:OptIn(org.maplibre.compose.util.DelicateMaplibreComposeApi::class)

package org.maplibre.compose.map

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiGate
import org.maplibre.compose.mlnffi.MlnFfiRuntime
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.fileUrlOf
import org.maplibre.compose.offline.DownloadProgress
import org.maplibre.compose.offline.DownloadStatus
import org.maplibre.compose.offline.OfflinePackDefinition
import org.maplibre.compose.offline.OfflineStorageState
import org.maplibre.compose.offline.offlineStorage
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleSnapshot
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.runMapTest
import org.maplibre.nativeffi.runtime.OfflineOperationHandle
import org.maplibre.spatialk.geojson.BoundingBox

class SharedMapRuntimeTest {
  @Test
  fun closing_one_child_drops_its_queued_work_and_cancelling_offline_creation_leaves_no_pack() =
    runBlocking {
      FfiTestPlatform.initialize()
      val cacheFile = FfiTestPlatform.createCacheFile()
      val options = MlnFfiRuntimeOptions(cacheFile, mainDispatcher = TestMainDispatcher())
      val runtime = createNativeMapRuntime(options)
      val owner = runtime.nativeOwner
      val entered = CompletableDeferred<Unit>()
      val release = MlnFfiGate()
      owner.awaitReady()
      val first = child(owner).also { it.start() }
      val second = child(owner).also { it.start() }
      try {
        first.submit {
          entered.complete(Unit)
          release.awaitUntilOpen()
        }
        withTimeout(5_000) { entered.await() }
        val dropped =
          async(start = CoroutineStart.UNDISPATCHED) {
            first.await { error("work queued on a closed child ran") }
          }
        val cancelled =
          async(start = CoroutineStart.UNDISPATCHED) {
            runtime.offlineStorage.create(
              OfflinePackDefinition.TilePyramid(
                "file:///unused-style.json",
                BoundingBox(-1.0, -1.0, 1.0, 1.0),
                1f,
                maxZoom = 0.0,
              )
            )
          }
        cancelled.cancelAndJoin()
        first.close()
        release.open()
        withTimeout(5_000) {
          assertNull(dropped.await())
          first.awaitClosed()
          assertEquals(true, second.await { true })
        }
      } finally {
        release.open()
        first.close()
        second.close()
        withTimeout(5_000) {
          first.awaitClosed()
          second.awaitClosed()
        }
        runtime.close()
        withTimeout(5_000) { runtime.awaitClosed() }
      }
      // Read the database through a fresh runtime: an abandoned native create could be absent from
      // the old manager's in-memory catalog but still leave a persisted region.
      val reopened = createNativeMapRuntime(options)
      try {
        val state =
          withTimeout(5_000) {
            reopened.offlineStorage.state.first { it !is OfflineStorageState.Loading }
          }
        assertTrue(assertIs<OfflineStorageState.Ready>(state).packs.isEmpty())
      } finally {
        reopened.close()
        withTimeout(5_000) { reopened.awaitClosed() }
        FfiTestPlatform.deleteCacheFile(cacheFile)
      }
    }

  @Test
  fun a_fatal_drain_failure_reaches_every_map_and_waits_for_child_release() = runBlocking {
    FfiTestPlatform.initialize()
    val cacheFile = FfiTestPlatform.createCacheFile()
    val runtime =
      createNativeMapRuntime(
        MlnFfiRuntimeOptions(
          cacheFile,
          mainDispatcher =
            TestMainDispatcher(
              loop = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
            ),
        )
      )
    val owner = runtime.nativeOwner
    owner.awaitReady()
    val retained = runtime.createMapState(BaseStyle.Empty)
    retained.withPlatformMap { map.camera.zoom }
    val expected = IllegalStateException("event drain failed")
    val firstFailure = CompletableDeferred<Throwable>()
    val secondFailure = CompletableDeferred<Throwable>()
    val first = child(owner) { firstFailure.complete(it) }
    val second = child(owner) { secondFailure.complete(it) }
    var operation: OfflineOperationHandle<*>? = null
    owner.onOfflineEvent = { _, _ ->
      operation?.close()
      operation = null
      throw expected
    }
    try {
      first.start()
      second.start()
      first.await {}
      second.await {}
      owner.post(
        MlnFfiRuntime.Task(
          run = { operation = it.startOfflineRegions() },
          reject = { throw it },
        )
      )
      withTimeout(5_000) {
        assertSame(expected, firstFailure.await())
        assertSame(expected, secondFailure.await())
      }
      assertTrue(runtime.isClosed, "a fatal pump failure must close its public runtime")
      first.close()
      val stopped = async(start = CoroutineStart.UNDISPATCHED) { owner.awaitClosed() }
      assertFalse(stopped.isCompleted, "the second map has not acknowledged renderer release")
      second.close()
      withTimeout(5_000) {
        first.awaitClosed()
        second.awaitClosed()
        stopped.await()
      }
      assertSame(expected, owner.failure)
      withTimeout(5_000) { runtime.awaitClosed() }
      assertTrue(retained.isClosed)
    } finally {
      first.close()
      second.close()
      owner.close()
      withTimeout(5_000) { owner.awaitClosed() }
      runtime.close()
      withTimeout(5_000) { runtime.awaitClosed() }
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
  }

  @Test
  fun live_maps_animate_alongside_snapshots_and_offline_downloads_and_close_independently():
    MapTestResult = runMapTest {
    FfiTestPlatform.initialize()
    val cacheFile = FfiTestPlatform.createCacheFile()
    val runtime =
      createNativeMapRuntime(
        MlnFfiRuntimeOptions(
          cacheFile,
          maximumCacheSizeBytes = 1_000_000,
          mainDispatcher = TestMainDispatcher(),
        )
      )
    try {
      BridgeMapFixture.create(runtime = runtime).use { first ->
        BridgeMapFixture.create(runtime = runtime).use { second ->
          first.whileRenderingOnRendererThread {
            second.whileRenderingOnRendererThread {
              runBlocking {
                for (fixture in listOf(first, second)) {
                  fixture.state.publishPresentation(
                    fixture.state.reservePresentation(),
                    fixture.session,
                  )
                  fixture.bindState(fixture.state)
                }
                first.loadStyle(BaseStyle.Empty)
                second.loadStyle(BaseStyle.Empty)
                for (fixture in listOf(first, second)) {
                  fixture.session.reconcileStyleRevision(StyleSnapshot.Empty) {}
                }
                assertSame(
                  first.session.loop.await { it.runtime() },
                  second.session.loop.await { it.runtime() },
                )
                val snapshot = runtime.createSnapshotter(BaseStyle.Empty)
                val styleFile = Path(requireNotNull(cacheFile.parent), "offline.json")
                SystemFileSystem.sink(styleFile).buffered().use {
                  it.writeString("""{"version":8,"sources":{},"layers":[]}""")
                }
                val pack =
                  runtime.offlineStorage.create(
                    OfflinePackDefinition.TilePyramid(
                      fileUrlOf(styleFile),
                      BoundingBox(-1.0, -1.0, 1.0, 1.0),
                      1f,
                      maxZoom = 0.0,
                    )
                  )
                runtime.offlineStorage.resume(pack)
                val download = async {
                  pack.downloadProgress.first {
                    it is DownloadProgress.Healthy && it.status == DownloadStatus.Complete
                  }
                }
                val capture = async { snapshot.capture(DpSize(32.dp, 32.dp)) }
                val firstAnimation =
                  async(start = CoroutineStart.UNDISPATCHED) {
                    first.state.animateCamera(
                      CameraUpdate(zoom = 3.0),
                      CameraAnimation.Ease(300.milliseconds),
                    )
                  }
                val secondAnimation =
                  async(start = CoroutineStart.UNDISPATCHED) {
                    second.state.animateCamera(
                      CameraUpdate(zoom = 5.0),
                      CameraAnimation.Ease(300.milliseconds),
                    )
                  }
                withTimeout(15_000) {
                  first.awaitUntil("shared animations, capture, and download") {
                    second.frame()
                    firstAnimation.isCompleted &&
                      secondAnimation.isCompleted &&
                      capture.isCompleted &&
                      download.isCompleted
                  }
                  firstAnimation.await()
                  secondAnimation.await()
                  assertEquals(32, capture.await().width)
                  download.await()
                }
                assertEquals(3.0, first.state.cameraPosition.zoom, 0.001)
                assertEquals(5.0, second.state.cameraPosition.zoom, 0.001)
                val beforeGesture = first.state.cameraPosition.center
                val otherTarget = second.state.cameraPosition.center
                val gesture = first.session.onGestureStarted()
                assertTrue(
                  gesture.acceptsCommands,
                  "the fixture did not publish a gesture-ready viewport",
                )
                val gestureCapture =
                  async(start = CoroutineStart.UNDISPATCHED) {
                    snapshot.capture(DpSize(32.dp, 32.dp))
                  }
                val metadata =
                  async(start = CoroutineStart.UNDISPATCHED) {
                    pack.setMetadata(byteArrayOf(1))
                  }
                first.session.moveBy(16.0, 0.0, gesture)
                first.session.onGestureEnded(gesture)
                first.awaitUntil("capture and offline update during a gesture") {
                  second.frame()
                  gestureCapture.isCompleted &&
                    metadata.isCompleted &&
                    first.state.cameraPosition.center != beforeGesture &&
                    !first.state.isCameraMoving
                }
                assertEquals(32, gestureCapture.await().width)
                metadata.await()
                assertEquals(otherTarget, second.state.cameraPosition.center)
                first.state.close()
                first.state.awaitClosed()
                snapshot.close()
                snapshot.awaitClosed()
                second.state.setCameraPosition(second.state.cameraPosition.copy(zoom = 7.0))
                second.awaitUntil("surviving map camera") {
                  second.state.cameraPosition.zoom == 7.0
                }
                assertEquals(7.0, second.session.loop.await { it.camera.zoom })
                runtime.offlineStorage.delete(pack)
                assertTrue(second.errors.isEmpty())
                // The runtime closes a still attached renderer and its retained map, then its
                // owner.
                runtime.close()
                withTimeout(10_000) { runtime.awaitClosed() }
              }
            }
          }
        }
      }
    } finally {
      runtime.close()
      withTimeout(10_000) { runtime.awaitClosed() }
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
  }

  private fun child(owner: MlnFfiRuntime, onFailure: (Throwable) -> Unit = {}) =
    MlnFfiMapRuntimeLoop(
      extent = MapExtent.fromLogical(1, 1, 1.0),
      owner = owner,
      getLogger = { null },
      onMapCreated = {},
      onEvent = { _, _ -> },
      onEventsDrained = {},
      requestFrame = {},
      onFailure = onFailure,
    )
}
