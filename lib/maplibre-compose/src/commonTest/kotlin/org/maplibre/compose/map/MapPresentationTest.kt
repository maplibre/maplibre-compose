package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.layers.LayerHandle
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.GeoJsonSourceHandle
import org.maplibre.compose.sources.VectorSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.RecordingStyleBinding
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection

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
  fun a_departed_presentation_rejects_cached_operations_and_delayed_camera_events() {
    val fixture = presentationFixture()
    fixture.state.releasePresentation(fixture.token, fixture.adapter)

    assertFailsWith<MapPresentationDetachedException> {
      fixture.presentation.setCameraPosition(CameraPosition(zoom = 4.0))
    }
    val viewportReads = fixture.adapter.viewportReads
    fixture.adapter.lastCameraPosition = CameraPosition(zoom = 7.0)
    assertNull(fixture.state.synchronizeCamera(fixture.adapter))
    assertEquals(viewportReads, fixture.adapter.viewportReads)
    assertEquals(CameraPosition(), fixture.state.cameraPosition)
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
  fun publishing_into_a_closed_state_is_inert() {
    val fixture = presentationFixture()
    fixture.state.close()

    fixture.state.publishPresentation(fixture.token, fixture.adapter)

    assertTrue(fixture.state.isClosed)
    assertNull(fixture.state.presentation)
    fixture.runtime.close()
  }

  @Test
  fun replacement_cleanup_failures_are_reported_on_close() = runTest {
    val runtime = mapRuntimeForTest()
    val state = runtime.createMapState()
    val firstToken = state.reservePresentation()
    val first = RetainedAdapter(failOnClose = true)
    state.publishPresentation(firstToken, first)
    state.releasePresentation(firstToken, first)
    testScheduler.advanceUntilIdle()

    val secondToken = state.reservePresentation()
    val second = RetainedAdapter(failOnClose = false)
    state.publishPresentation(secondToken, second)
    testScheduler.advanceUntilIdle()

    state.close()
    val failure = assertFailsWith<MapStateCleanupException> { state.awaitClosed() }
    assertTrue(failure.message.orEmpty().contains("cleanup failed"))
    runtime.close()
  }

  @Test
  fun a_durable_callback_updates_style_load_state_after_the_presentation_leaves() = runTest {
    val runtime = mapRuntimeForTest()
    val state = runtime.createMapState()
    val token = state.reservePresentation()
    val adapter = RetainedAdapter(failOnClose = false)
    state.publishPresentation(token, adapter)
    state.releasePresentation(token, adapter)
    testScheduler.advanceUntilIdle()

    state.durableStyleCallbacks().onMapFailLoading(adapter, "style refused")

    assertTrue(state.style.loadState is StyleLoadState.Failed)
    state.close()
    runtime.close()
  }

  @Test
  fun a_live_source_handle_is_ready_bound_and_cannot_target_a_replacement_style() {
    val fixture = presentationFixture()
    val firstStyle =
      RecordingStyleBinding(
        sources =
          listOf(
            GeoJsonSource(
              id = "points",
              data = GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[]}"""),
              options = GeoJsonOptions(),
            )
          )
      )

    assertNull(fixture.state.style.source("points"))
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, firstStyle)
    assertNull(fixture.state.style.source("points"))
    fixture.state.durableStyleCallbacks().onMapFinishedLoading(fixture.adapter)

    val handle = assertIs<GeoJsonSourceHandle>(fixture.state.style.source("points"))
    assertNull(fixture.state.style.source("missing"))
    handle.setFeatureState("7", buildJsonObject { put("selected", true) })
    assertEquals(
      true,
      firstStyle.featureState("points", null, "7")["selected"]?.jsonPrimitive?.boolean,
    )

    fixture.state.style.baseStyle = BaseStyle.Json("replacement")
    assertFailsWith<IllegalStateException> {
      handle.setFeatureState("7", buildJsonObject { put("stale", true) })
    }
    val replacement =
      RecordingStyleBinding(
        sources =
          listOf(
            GeoJsonSource(
              id = "points",
              data = GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[]}"""),
              options = GeoJsonOptions(),
            )
          )
      )
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, replacement)
    fixture.state.durableStyleCallbacks().onMapFinishedLoading(fixture.adapter)

    assertFailsWith<IllegalStateException> {
      handle.setFeatureState("7", buildJsonObject { put("stale", true) })
    }
    assertEquals(JsonObject(emptyMap()), replacement.featureState("points", null, "7"))
    fixture.close()
  }

  @Test
  fun a_typed_source_handle_rejects_a_same_id_source_type_replacement() {
    val fixture = presentationFixture()
    val geoJson =
      GeoJsonSource(
        id = "shared",
        data = GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[]}"""),
        options = GeoJsonOptions(),
      )
    fixture.state.desiredStyleRevision =
      DesiredStyleRevision(
        sources = listOf(geoJson.definition()),
        layers = emptyList(),
        images = emptyList(),
      )
    val loadedStyle = RecordingStyleBinding(sources = listOf(geoJson))
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, loadedStyle)
    fixture.state.durableStyleCallbacks().onMapFinishedLoading(fixture.adapter)
    val handle = assertIs<GeoJsonSourceHandle>(fixture.state.style.source("shared"))
    val vector = VectorSource("shared", "https://example.com/tiles.json")

    fixture.state.beginStyleRevision(
      fixture.adapter,
      DesiredStyleRevision(
        sources = listOf(vector.definition()),
        layers = emptyList(),
        images = emptyList(),
      ),
    )
    assertFailsWith<IllegalStateException> {
      handle.setFeatureState("7", buildJsonObject { put("stale", true) })
    }
    assertEquals(JsonObject(emptyMap()), loadedStyle.featureState("shared", null, "7"))

    loadedStyle.replaceSource(vector)
    fixture.state.markStyleReady(fixture.adapter)

    assertFailsWith<IllegalStateException> { handle.getFeatureState("7") }
    assertEquals(JsonObject(emptyMap()), loadedStyle.featureState("shared", null, "7"))
    fixture.close()
  }

  @Test
  fun geojson_cluster_queries_have_empty_fallbacks_for_a_non_cluster_feature() = runTest {
    val fixture = presentationFixture()
    val loadedStyle =
      RecordingStyleBinding(
        sources =
          listOf(
            GeoJsonSource(
              id = "points",
              data = GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[]}"""),
              options = GeoJsonOptions(),
            )
          )
      )
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, loadedStyle)
    fixture.state.durableStyleCallbacks().onMapFinishedLoading(fixture.adapter)
    val handle = assertIs<GeoJsonSourceHandle>(fixture.state.style.source("points"))
    val point =
      buildFeatureCollection<Geometry, JsonObject?> {
          addFeature(geometry = Point(Position(0.0, 0.0)))
        }
        .features
        .single()

    assertFalse(handle.isCluster(point))
    assertEquals(0.0, handle.getClusterExpansionZoom(point))
    assertTrue(handle.getClusterChildren(point).features.isEmpty())
    assertTrue(handle.getClusterLeaves(point, limit = 1, offset = 0).features.isEmpty())
    fixture.close()
  }

  @Test
  fun replacing_a_retained_engine_invalidates_its_style_handles_before_publication() = runTest {
    val runtime = mapRuntimeForTest()
    val state = runtime.createMapState()
    val firstToken = state.reservePresentation()
    val first = RetainedAdapter(failOnClose = false)
    state.publishPresentation(firstToken, first)
    val firstStyle =
      RecordingStyleBinding(
        sources =
          listOf(
            GeoJsonSource(
              id = "points",
              data = GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[]}"""),
              options = GeoJsonOptions(),
            )
          )
      )
    state.durableStyleCallbacks().onStyleChanged(first, firstStyle)
    state.durableStyleCallbacks().onMapFinishedLoading(first)
    val handle = assertIs<GeoJsonSourceHandle>(state.style.source("points"))
    state.releasePresentation(firstToken, first)
    testScheduler.advanceUntilIdle()

    val secondToken = state.reservePresentation()
    state.publishPresentation(secondToken, RetainedAdapter(failOnClose = false))

    assertFailsWith<IllegalStateException> {
      handle.setFeatureState("7", buildJsonObject { put("stale", true) })
    }
    assertEquals(JsonObject(emptyMap()), firstStyle.featureState("points", null, "7"))
    state.close()
    runtime.close()
  }

  @Test
  fun a_live_layer_handle_reads_and_writes_only_its_loaded_style() {
    val fixture = presentationFixture()
    val loadedStyle = RecordingStyleBinding(layers = listOf(BackgroundLayer("background")))
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, loadedStyle)
    fixture.state.durableStyleCallbacks().onMapFinishedLoading(fixture.adapter)

    val handle = assertIs<LayerHandle>(fixture.state.style.layer("background"))
    assertNull(fixture.state.style.layer("missing"))
    handle.setPaintProperty("background-opacity", JsonPrimitive(0.5))
    assertEquals(JsonPrimitive(0.5), handle.getProperty("background-opacity"))

    loadedStyle.invalidate()
    assertFailsWith<IllegalStateException> { handle.getProperty("background-opacity") }
    fixture.close()
  }

  @Test
  fun publication_happens_after_the_adapter_accepts_initial_map_state() {
    val runtime = mapRuntimeForTest()
    val initialCamera = CameraPosition(target = Position(12.0, 34.0), zoom = 8.0)
    val state = runtime.createMapState(initialCameraPosition = initialCamera)
    val token = state.reservePresentation()
    val adapter = PresentationTestAdapter { state.presentation }

    state.publishPresentation(token, adapter)

    assertFalse(adapter.presentationWasVisibleWhileConfiguring)
    assertEquals(initialCamera, adapter.lastCameraPosition)
    assertTrue(state.presentation != null)
    state.close()
    runtime.close()
  }

  @Test
  fun viewport_observations_are_null_before_the_first_viewport() {
    val fixture = presentationFixture()

    assertNull(fixture.presentation.getVisibleRegion())
    assertNull(fixture.presentation.getVisibleBoundingBox())
    assertNull(fixture.presentation.metersPerDpAtLatitude(0.0))
    fixture.close()
  }

  @Test
  fun a_rendered_query_waits_for_the_first_viewport() = runTest {
    val fixture = presentationFixture()
    supervisorScope {
      val query = async { fixture.presentation.queryRenderedFeatures(DpOffset.Zero) }
      testScheduler.runCurrent()

      assertFalse(fixture.adapter.queryStarted.isCompleted)

      fixture.presentation.updateViewport(testViewport())
      fixture.adapter.queryStarted.await()
      fixture.state.releasePresentation(fixture.token, fixture.adapter)

      assertFailsWith<MapPresentationDetachedException> { query.await() }
    }
    fixture.close()
  }

  @Test
  fun detachment_fails_an_active_query_instead_of_targeting_another_presentation() = runTest {
    val fixture = presentationFixture()
    fixture.presentation.updateViewport(testViewport())
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

private class RetainedAdapter(private val failOnClose: Boolean) : PresentationTestAdapter() {
  override val retainsEngineBetweenPresentations: Boolean = true

  override suspend fun detachPresentation() = Unit

  override suspend fun awaitClosed() {
    if (failOnClose) error("cleanup failed")
  }
}

private open class PresentationTestAdapter(
  private val currentPresentation: () -> MapPresentation? = { null }
) : MapAdapter {
  var lastCameraPosition = CameraPosition()
  var presentationWasVisibleWhileConfiguring = false
  var viewportReads = 0
  val queryStarted = CompletableDeferred<Unit>()
  val animationStarted = CompletableDeferred<Unit>()
  val finishAnimation = CompletableDeferred<Unit>()

  override fun close() = Unit

  open override suspend fun awaitClosed() = Unit

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

  override fun setBaseStyle(style: BaseStyle) {
    presentationWasVisibleWhileConfiguring =
      presentationWasVisibleWhileConfiguring || currentPresentation() != null
  }

  override suspend fun reconcileStyleRevision(revision: DesiredStyleRevision): Boolean = true

  override suspend fun replayStyleRevision(revision: DesiredStyleRevision) = Unit

  override fun getCameraPosition(): CameraPosition = lastCameraPosition

  override fun setCameraPosition(cameraPosition: CameraPosition) {
    presentationWasVisibleWhileConfiguring =
      presentationWasVisibleWhileConfiguring || currentPresentation() != null
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

  override fun getViewport(): Viewport? {
    viewportReads++
    return null
  }

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

private fun testViewport(): Viewport =
  Viewport(
    size = DpSize(100.dp, 100.dp),
    visibleBoundingBox = BoundingBox(Position(-1.0, -1.0), Position(1.0, 1.0)),
    visibleRegion =
      VisibleRegion(
        farLeft = Position(-1.0, 1.0),
        farRight = Position(1.0, 1.0),
        nearLeft = Position(-1.0, -1.0),
        nearRight = Position(1.0, -1.0),
      ),
    metersPerDpAtTarget = 1.0,
  )
