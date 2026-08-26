package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.sources.RasterSource
import org.maplibre.compose.sources.TileSetOptions
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.OpRecordingStyleBinding
import org.maplibre.compose.style.StyleHostDispatcher
import org.maplibre.compose.style.StyleState
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

private fun testSource(id: String) =
  RasterSource(id, listOf("https://example.invalid/{z}/{x}/{y}.png"), TileSetOptions())

/** Records every adapter call by name, standing in for a render session. */
private class FakeMapAdapter : MapAdapter {
  val calls: MutableList<String> = mutableListOf()

  override suspend fun animateCameraPosition(finalPosition: CameraPosition, duration: Duration) {
    calls += "animateCameraPosition"
  }

  override suspend fun animateCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
    duration: Duration,
  ) {
    calls += "animateCameraPosition"
  }

  override fun setBaseStyle(style: BaseStyle) {
    calls += "setBaseStyle"
  }

  override fun getCameraPosition(): CameraPosition {
    calls += "getCameraPosition"
    return CameraPosition()
  }

  override fun setCameraPosition(cameraPosition: CameraPosition) {
    calls += "setCameraPosition"
  }

  override fun setCameraPadding(padding: PaddingValues) {
    calls += "setCameraPadding"
  }

  override fun setCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
  ) {
    calls += "setCameraPosition"
  }

  override fun setCameraBoundingBox(boundingBox: BoundingBox?) {
    calls += "setCameraBoundingBox"
  }

  override fun setMaxZoom(maxZoom: Double) {
    calls += "setMaxZoom"
  }

  override fun setMinZoom(minZoom: Double) {
    calls += "setMinZoom"
  }

  override fun setMinPitch(minPitch: Double) {
    calls += "setMinPitch"
  }

  override fun setMaxPitch(maxPitch: Double) {
    calls += "setMaxPitch"
  }

  override fun getVisibleBoundingBox(): BoundingBox = error("unused in these tests")

  override fun getVisibleRegion(): VisibleRegion = error("unused in these tests")

  override fun getViewport(): Viewport? {
    calls += "getViewport"
    return null
  }

  override fun setRenderSettings(value: RenderOptions) {
    calls += "setRenderSettings"
  }

  override fun setGestureSettings(value: GestureOptions) {
    calls += "setGestureSettings"
  }

  override fun setTileLodSettings(value: TileLodOptions) {
    calls += "setTileLodSettings"
  }

  override fun positionFromScreenLocation(offset: DpOffset): Position? = null

  override fun screenLocationFromPosition(position: Position): DpOffset? = null

  override suspend fun queryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature<Geometry, JsonObject?>> = emptyList()

  override suspend fun queryRenderedFeatures(
    rect: DpRect,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature<Geometry, JsonObject?>> = emptyList()

  override fun metersPerDpAtLatitude(latitude: Double): Double = 1.0
}

private class TestHostDispatcher(override val dispatcher: CoroutineDispatcher) :
  StyleHostDispatcher {
  var closed = false
    private set

  override fun close() {
    closed = true
  }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MapStateTest {

  private fun TestScope.mapState(
    cameraState: CameraState,
    styleState: StyleState,
    hostDispatcher: TestHostDispatcher = TestHostDispatcher(StandardTestDispatcher(testScheduler)),
  ) =
    MapState(
      cameraState = cameraState,
      styleState = styleState,
      density = Density(1f),
      layoutDirection = LayoutDirection.Ltr,
      logger = null,
      hostDispatcher = hostDispatcher,
    )

  @Test
  fun survives_a_detach_attach_cycle_and_rewires_a_new_session() = runTest {
    val cameraState = CameraState(CameraPosition())
    val styleState = StyleState()
    val state = mapState(cameraState, styleState)
    val source = testSource("tiles")

    state.setStyleContent { RasterLayer(id = "raster", source = source) }
    state.startStyleComposition()

    val first = FakeMapAdapter()
    val firstBinding = OpRecordingStyleBinding()
    state.attachSession(first)
    state.callbacks.onStyleChanged(first, firstBinding)
    testScheduler.advanceUntilIdle()

    assertSame(first, cameraState.map)
    assertEquals(1, first.calls.count { it == "setCameraPosition" })
    assertEquals(listOf("addSource:tiles", "addLayer:raster"), firstBinding.ops.toList())

    state.detachSession()
    testScheduler.advanceUntilIdle()
    val firstCallsAfterDetach = first.calls.toList()
    val firstOpsAfterDetach = firstBinding.ops.toList()

    assertNull(cameraState.map)
    assertFalse(state.styleNode.binding.isLoaded)
    assertTrue(styleState.sources.isEmpty())

    val second = FakeMapAdapter()
    val secondBinding = OpRecordingStyleBinding()
    state.attachSession(second)
    state.callbacks.onStyleChanged(second, secondBinding)
    testScheduler.advanceUntilIdle()

    // The new session gets the deferred camera and a fresh apply of the same desired state.
    assertSame(second, cameraState.map)
    assertEquals(1, second.calls.count { it == "setCameraPosition" })
    assertEquals(listOf("addSource:tiles", "addLayer:raster"), secondBinding.ops.toList())

    // The detached session and its dead style saw nothing after the detach.
    assertEquals(firstCallsAfterDetach, first.calls.toList())
    assertEquals(firstOpsAfterDetach, firstBinding.ops.toList())

    state.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun close_after_detach_shuts_down_the_recomposer_and_the_dispatcher() = runTest {
    val hostDispatcher = TestHostDispatcher(StandardTestDispatcher(testScheduler))
    val state = mapState(CameraState(CameraPosition()), StyleState(), hostDispatcher)
    state.setStyleContent { RasterLayer(id = "raster", source = testSource("tiles")) }
    state.startStyleComposition()

    val adapter = FakeMapAdapter()
    state.attachSession(adapter)
    state.callbacks.onStyleChanged(adapter, OpRecordingStyleBinding())
    testScheduler.advanceUntilIdle()

    state.detachSession()
    state.close()
    testScheduler.advanceUntilIdle()

    assertEquals(Recomposer.State.ShutDown, state.host.recomposer.currentState.value)
    assertTrue(hostDispatcher.closed)
  }
}
