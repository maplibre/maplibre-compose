package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalCoroutinesApi::class)
class MapPresentationTest {

  @Test
  fun closure_updates_the_observable_runtime_and_map_flags_immediately() {
    val runtime = mapRuntimeForTest()
    val state = runtime.createMapState()

    assertFalse(runtime.isClosed)
    assertFalse(state.isClosed)
    state.close()
    assertTrue(state.isClosed)
    runtime.close()
    assertTrue(runtime.isClosed)
  }

  @Test
  fun an_accepted_camera_set_updates_the_durable_map_position() {
    val fixture = presentationFixture()
    val position = CameraPosition(target = Position(12.0, 34.0), zoom = 8.0)

    fixture.presentation.setCameraPosition(position)

    assertEquals(position, fixture.state.cameraPosition)
    assertEquals(position, fixture.adapter.lastCameraPosition)
    fixture.close()
  }

  @Test
  fun a_cached_presentation_fails_immediately_after_detachment() {
    val fixture = presentationFixture()
    fixture.state.releasePresentation(fixture.token, fixture.adapter)

    assertFailsWith<MapPresentationDetachedException> {
      fixture.presentation.setCameraPosition(CameraPosition(zoom = 4.0))
    }
    fixture.close()
  }

  @Test
  fun presentation_options_update_only_the_current_lease() {
    val fixture = presentationFixture()
    val options = MapPresentationOptions(zoomRange = 2f..18f, pitchRange = 3f..45f)

    fixture.state.publishPresentation(fixture.token, fixture.adapter, options)

    assertEquals(options, fixture.presentation.options)
    fixture.close()
  }

  @Test
  fun detachment_fails_an_active_query_instead_of_targeting_another_presentation() = runTest {
    val fixture = presentationFixture()
    supervisorScope {
      val query = async { fixture.presentation.queryRenderedFeatures(DpOffset.Zero) }
      fixture.adapter.queryStarted.await()

      fixture.state.releasePresentation(fixture.token, fixture.adapter)

      assertFailsWith<MapPresentationDetachedException> { query.await() }
    }
    fixture.close()
  }

  @Test
  fun a_replacement_animation_cancels_only_the_previous_camera_mutation() = runTest {
    val fixture = presentationFixture()
    val first = async {
      fixture.presentation.animateCameraPosition(CameraPosition(zoom = 2.0), 1.seconds)
    }
    fixture.adapter.animationStarted.await()
    val second = async {
      fixture.presentation.animateCameraPosition(CameraPosition(zoom = 3.0), 1.seconds)
    }
    testScheduler.runCurrent()

    assertTrue(first.isCancelled)
    assertFalse(second.isCompleted)
    assertTrue(fixture.presentation.isValid)

    fixture.adapter.finishAnimation.complete(Unit)
    second.await()
    fixture.close()
  }
}

private data class PresentationFixture(
  val runtime: MapRuntime,
  val state: MapState,
  val token: MapPresentationToken,
  val adapter: PresentationTestAdapter,
  val presentation: MapPresentation,
) {
  fun close() {
    state.close()
    runtime.close()
  }
}

private fun presentationFixture(): PresentationFixture {
  val runtime = mapRuntimeForTest()
  val state = runtime.createMapState()
  val token = state.reservePresentation()
  val adapter = PresentationTestAdapter()
  state.publishPresentation(token, adapter)
  return PresentationFixture(runtime, state, token, adapter, requireNotNull(state.presentation))
}

private class PresentationTestAdapter : MapAdapter {
  var lastCameraPosition = CameraPosition()
  val queryStarted = CompletableDeferred<Unit>()
  val animationStarted = CompletableDeferred<Unit>()
  val finishAnimation = CompletableDeferred<Unit>()

  override fun close() = Unit

  override suspend fun awaitClosed() = Unit

  override suspend fun animateCameraPosition(finalPosition: CameraPosition, duration: Duration) {
    animationStarted.complete(Unit)
    finishAnimation.await()
  }

  override suspend fun animateCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
    duration: Duration,
  ) = awaitCancellation()

  override fun setBaseStyle(style: BaseStyle) = Unit

  override suspend fun reconcileStyleRevision(revision: DesiredStyleRevision): Boolean = true

  override suspend fun replayStyleRevision(revision: DesiredStyleRevision) = Unit

  override fun getCameraPosition(): CameraPosition = lastCameraPosition

  override fun setCameraPosition(cameraPosition: CameraPosition) {
    lastCameraPosition = cameraPosition
  }

  override fun setCameraPadding(padding: PaddingValues) = Unit

  override fun setCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
  ) = Unit

  override fun setCameraConstraints(value: CameraConstraints) = Unit

  override fun getVisibleBoundingBox(): BoundingBox =
    BoundingBox(Position(-1.0, -1.0), Position(1.0, 1.0))

  override fun getVisibleRegion(): VisibleRegion =
    VisibleRegion(
      farLeft = Position(-1.0, 1.0),
      farRight = Position(1.0, 1.0),
      nearLeft = Position(-1.0, -1.0),
      nearRight = Position(1.0, -1.0),
    )

  override fun getViewport(): Viewport? = null

  override fun setRenderSettings(value: RenderOptions) = Unit

  override fun setGestureSettings(value: GestureOptions) = Unit

  override fun setTileLodSettings(value: TileLodOptions) = Unit

  override fun positionFromScreenLocation(offset: DpOffset): Position? = null

  override fun screenLocationFromPosition(position: Position): DpOffset? = null

  override suspend fun queryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature<Geometry, JsonObject?>> {
    queryStarted.complete(Unit)
    awaitCancellation()
  }

  override suspend fun queryRenderedFeatures(
    rect: DpRect,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature<Geometry, JsonObject?>> = awaitCancellation()

  override fun metersPerDpAtLatitude(latitude: Double): Double = 1.0
}
