package org.maplibre.compose.testing

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.map.MapAdapter
import org.maplibre.compose.map.MapEvent
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.map.MapRuntime
import org.maplibre.compose.map.MapStateCallbacks
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.map.createNativeMapRuntime
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.TestMapMain
import org.maplibre.compose.resource.MapResourceProvider
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleSnapshot

/** The map runs on threads of its own, so blocking the test thread in a wait stops nothing. */
internal class MlnFfiMapFixture(val bridge: BridgeMapFixture) : MapFixture {

  override val state = bridge.state

  init {
    state.setCameraPosition(CameraPosition(zoom = 0.0))
  }

  override val session: MapAdapter
    get() = bridge.session

  override val gestures: CameraInputTarget
    get() = bridge.session

  override val style: StyleBinding?
    get() = bridge.style

  override val events: RecordingList<String>
    get() = bridge.events

  override val engineEvents: RecordingList<MapEvent>
    get() = bridge.engineEvents

  override val errors: RecordingList<String>
    get() = bridge.errors

  override suspend fun loadStyle(style: BaseStyle, timeout: Duration) {
    bridge.loadStyle(style, timeout)
    bridge.session.reconcileStyleRevision(StyleSnapshot.Empty)
    MapStateCallbacks(state).onStyleReady(bridge.session)
    pumpUntil("the loaded style to be ready", timeout) {
      state.style.loadState == StyleLoadState.Ready
    }
  }

  override suspend fun awaitMapReady(timeout: Duration) {
    bridge.awaitRendered(timeout)
  }

  override fun resize(extent: MapExtent) {
    bridge.extent = extent
  }

  override suspend fun pump(frames: Int) {
    bridge.awaitFrames(frames)
  }

  override suspend fun pumpUntil(
    description: String,
    timeout: Duration,
    condition: suspend () -> Boolean,
  ) {
    bridge.awaitUntil(description, timeout, condition)
  }

  /** Reads the last presented frame, once the map has rendered. */
  override suspend fun readPixel(x: Int, y: Int): RgbaPixel {
    bridge.awaitRendered()
    return bridge.readPixel(x, y)
  }

  override suspend fun settle(quiet: Duration, timeout: Duration) {
    bridge.awaitSettled(quiet, timeout)
  }

  override suspend fun <T> awaitWhileRendering(
    description: String,
    timeout: Duration,
    block: suspend () -> T,
  ): T = bridge.awaitWhileRendering(description, timeout, block)

  override fun closeSession() {
    state.close()
  }

  override fun close() {
    bridge.close()
  }
}

internal actual fun createMapFixture(extent: MapExtent): MapFixture =
  MlnFfiMapFixture(BridgeMapFixture.create(extent))

internal actual suspend fun withTestMapRuntime(
  provider: MapResourceProvider?,
  block: suspend (MapRuntime) -> Unit,
) {
  FfiTestPlatform.initialize()
  val cacheFile = FfiTestPlatform.createCacheFile()
  val runtime =
    createNativeMapRuntime(
      MlnFfiRuntimeOptions(
        cacheFile = cacheFile,
        maximumCacheSizeBytes = null,
        resourceProvider = provider,
        mainDispatcher = TestMapMain.dispatcher,
      ),
    )
  try {
    block(runtime)
  } finally {
    runtime.close()
    runtime.awaitClosed()
    FfiTestPlatform.deleteCacheFile(cacheFile)
  }
}

internal actual val mapLibreFlavor: MapLibreFlavor = MapLibreFlavor.Native

actual typealias MapTestResult = Unit

/** Runs [block] on [TestMapMain], the maps' main thread, and then the cleanup it deferred. */
internal actual fun runMapTest(block: suspend CoroutineScope.() -> Unit): MapTestResult =
  runTest(timeout = 3.minutes) {
    withContext(TestMapMain.dispatcher) {
      try {
        block()
      } finally {
        withContext(NonCancellable) { TestMapMain.runCleanups() }
      }
    }
  }

internal actual fun skipMapTest(reason: String): Nothing =
  org.maplibre.compose.mlnffi.FfiTestPlatform.skip(reason)
