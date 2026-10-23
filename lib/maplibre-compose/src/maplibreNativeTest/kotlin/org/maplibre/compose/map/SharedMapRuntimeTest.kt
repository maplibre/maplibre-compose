@file:OptIn(org.maplibre.compose.util.DelicateMaplibreComposeApi::class)

package org.maplibre.compose.map

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.TestMapMain
import org.maplibre.compose.mlnffi.fileUrlOf
import org.maplibre.compose.offline.DownloadProgress
import org.maplibre.compose.offline.DownloadStatus
import org.maplibre.compose.offline.OfflinePackDefinition
import org.maplibre.compose.offline.offlineStorage
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleSnapshot
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.testing.skipUnlessSnapshotsAvailable
import org.maplibre.spatialk.geojson.BoundingBox

class SharedMapRuntimeTest {
  @Test
  fun live_maps_animate_alongside_snapshots_and_offline_downloads_and_close_independently():
    MapTestResult = runMapTest {
    FfiTestPlatform.initialize()
    skipUnlessSnapshotsAvailable()
    val cacheFile = FfiTestPlatform.createCacheFile()
    val runtime =
      createNativeMapRuntime(
        MlnFfiRuntimeOptions(
          cacheFile,
          maximumCacheSizeBytes = 1_000_000,
          mainDispatcher = TestMapMain.dispatcher,
        ),
      )
    try {
      BridgeMapFixture.create(runtime = runtime).use { first ->
        BridgeMapFixture.create(runtime = runtime).use { second ->
          first.loadStyle(BaseStyle.Empty)
          second.loadStyle(BaseStyle.Empty)
          for (fixture in listOf(first, second)) {
            fixture.session.reconcileStyleRevision(StyleSnapshot.Empty)
          }
          assertSame(first.state.runtime, second.state.runtime)
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
              ),
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
                CameraAnimation.Ease { duration = 300.milliseconds },
              )
            }
          val secondAnimation =
            async(start = CoroutineStart.UNDISPATCHED) {
              second.state.animateCamera(
                CameraUpdate(zoom = 5.0),
                CameraAnimation.Ease { duration = 300.milliseconds },
              )
            }
          first.awaitUntil("shared animations, capture, and download", 15.seconds) {
            firstAnimation.isCompleted &&
              secondAnimation.isCompleted &&
              capture.isCompleted &&
              download.isCompleted
          }
          firstAnimation.await()
          secondAnimation.await()
          assertEquals(32, capture.await().width)
          download.await()
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
            async(start = CoroutineStart.UNDISPATCHED) { snapshot.capture(DpSize(32.dp, 32.dp)) }
          val metadata =
            async(start = CoroutineStart.UNDISPATCHED) { pack.setMetadata(byteArrayOf(1)) }
          first.session.moveBy(16.0, 0.0, gesture)
          first.session.onGestureEnded(gesture)
          first.awaitUntil("capture and offline update during a gesture") {
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
          second.awaitUntil("surviving map camera") { second.state.cameraPosition.zoom == 7.0 }
          assertEquals(7.0, second.session.readMap { it.camera().zoom })
          runtime.offlineStorage.delete(pack)
          assertTrue(second.errors.isEmpty())
          // The runtime closes a still attached renderer and its retained map, then its owner.
          runtime.close()
          withTimeout(10.seconds) { runtime.awaitClosed() }
        }
      }
    } finally {
      runtime.close()
      withTimeout(10.seconds) { runtime.awaitClosed() }
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
  }
}
