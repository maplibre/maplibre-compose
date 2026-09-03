package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
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
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.nil
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.ProjectionType
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.layers.LayerHandle
import org.maplibre.compose.overlay.attributions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.GeoJsonSourceHandle
import org.maplibre.compose.sources.TileSetOptions
import org.maplibre.compose.sources.VectorSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesiredStyleLayer
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.ImageSnapshot
import org.maplibre.compose.style.Light
import org.maplibre.compose.style.Projection
import org.maplibre.compose.style.ProjectionTransition
import org.maplibre.compose.style.RecordingStyleBinding
import org.maplibre.compose.style.Sky
import org.maplibre.compose.style.StyleHandleException
import org.maplibre.compose.style.StyleImageDefinition
import org.maplibre.compose.style.TransitionOptions
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
  fun closing_map_state_closes_a_bound_session_before_it_is_published() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Demo)
    val session = BoundLifecycleSession()
    session.lifecycle = state.lifecycle.bind(session)
    session.lifecycle.attach()

    state.close()
    state.awaitClosed()

    assertEquals(
      listOf("create", "attach", "detach", "destroy", "close resources"),
      session.commands,
    )
    runtime.close()
  }

  @Test
  fun a_bound_but_unpublished_session_cannot_mutate_durable_style_state() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Demo)
    val session = BoundLifecycleSession()
    session.lifecycle = state.lifecycle.bind(session)
    session.lifecycle.attach()

    assertFalse(state.updateLoadedStyle(session, RecordingStyleBinding()))
    assertFalse(state.markStyleReady(session))
    assertEquals(StyleLoadState.Pending, state.style.loadState)

    state.close()
    state.awaitClosed()
    runtime.close()
  }

  @Test
  fun a_session_that_closes_itself_is_retired_and_its_failure_reaches_map_closure() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Demo)
    val finishCleanup = CompletableDeferred<Unit>()
    val session = BoundLifecycleSession(failOnClose = true, finishCleanup = finishCleanup)
    session.lifecycle = state.lifecycle.bind(session)
    session.lifecycle.attach()
    val token = state.reservePresentation()
    state.publishPresentation(token, session)
    assertSame(session, state.currentMapAttachment?.adapter)
    assertTrue(state.updateLoadedStyle(session, RecordingStyleBinding()))
    assertTrue(state.markStyleReady(session))
    assertEquals(StyleLoadState.Ready, state.style.loadState)

    session.close()

    assertNull(state.currentMapAttachment)
    assertNull(state.retainedAdapter(session.presentationCompatibilityKey))
    assertFalse(state.acceptsPresentationEvent(session))
    assertEquals(StyleLoadState.Pending, state.style.loadState)
    assertFalse(session.lifecycle.state == MapLifecycleState.Closed)
    finishCleanup.complete(Unit)
    assertFailsWith<MapCleanupException> { session.awaitClosed() }
    testScheduler.runCurrent()

    state.durableStyleCallbacks().onMapFailLoading(session, "stale callback")
    assertFalse(state.style.loadState is StyleLoadState.Failed)

    val replacement = PresentationTestAdapter()
    val replacementToken = state.reservePresentation()
    state.publishPresentation(replacementToken, replacement)
    assertSame(replacement, state.currentMapAttachment?.adapter)

    state.close()
    val failure = assertFailsWith<MapCleanupException> { state.awaitClosed() }
    assertTrue(
      generateSequence(failure as Throwable) { it.cause }
        .any { it.message.orEmpty().contains("bound session cleanup failed") }
    )
    runtime.close()
  }

  @Test
  fun a_detached_retained_session_that_closes_itself_invalidates_its_style() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Demo)
    val finishCleanup = CompletableDeferred<Unit>()
    val session = BoundLifecycleSession(finishCleanup = finishCleanup)
    session.lifecycle = state.lifecycle.bind(session)
    session.lifecycle.attach()
    val token = state.reservePresentation()
    state.publishPresentation(token, session)
    assertTrue(state.updateLoadedStyle(session, RecordingStyleBinding()))
    assertTrue(state.markStyleReady(session))

    state.releasePresentation(token, session)
    testScheduler.runCurrent()
    assertNull(state.currentMapAttachment)
    assertSame(session, state.retainedAdapter(session.presentationCompatibilityKey))
    assertEquals(StyleLoadState.Ready, state.style.loadState)

    session.close()

    assertNull(state.retainedAdapter(session.presentationCompatibilityKey))
    assertEquals(StyleLoadState.Pending, state.style.loadState)
    assertFalse(session.lifecycle.state == MapLifecycleState.Closed)
    finishCleanup.complete(Unit)
    session.awaitClosed()
    state.close()
    state.awaitClosed()
    runtime.close()
  }

  @Test
  fun a_new_presentation_can_reserve_while_the_previous_one_is_still_detaching() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Demo)
    val first = BlockingDetachAdapter()
    val firstToken = state.reservePresentation(MapPresentationOwnerToken())
    state.publishPresentation(firstToken, first)

    state.releasePresentation(firstToken, first)
    first.detachStarted.await()
    assertNull(state.currentMapAttachment)

    val replacement = PresentationTestAdapter()
    val replacementToken = state.reservePresentation(MapPresentationOwnerToken())
    state.publishPresentation(replacementToken, replacement)
    assertSame(replacement, state.currentMapAttachment?.adapter)

    first.finishDetach.complete(Unit)
    testScheduler.runCurrent()
    assertSame(replacement, state.currentMapAttachment?.adapter)
    state.close()
    state.awaitClosed()
    runtime.close()
  }

  @Test
  fun closure_reports_a_superseded_presentations_in_flight_detach_failure() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Demo)
    val first = BlockingDetachAdapter(failOnDetach = true)
    val firstToken = state.reservePresentation(MapPresentationOwnerToken())
    state.publishPresentation(firstToken, first)
    state.releasePresentation(firstToken, first)
    first.detachStarted.await()

    val replacement = PresentationTestAdapter()
    val replacementToken = state.reservePresentation(MapPresentationOwnerToken())
    state.publishPresentation(replacementToken, replacement)
    state.close()

    first.finishDetach.complete(Unit)
    val failure = assertFailsWith<MapCleanupException> { state.awaitClosed() }
    assertTrue(
      generateSequence(failure as Throwable) { it.cause }.any { it.message == "detach failed" }
    )
    runtime.close()
  }

  @Test
  fun closure_reports_an_in_flight_session_detach_failure_once() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Demo)
    val finishDetach = CompletableDeferred<Unit>()
    val detachStarted = CompletableDeferred<Unit>()
    val session =
      BoundLifecycleSession(
        failOnDetach = true,
        finishDetach = finishDetach,
        detachStarted = detachStarted,
      )
    session.lifecycle = state.lifecycle.bind(session)
    session.lifecycle.attach()
    val token = state.reservePresentation()
    state.publishPresentation(token, session)

    state.releasePresentation(token, session)
    detachStarted.await()
    state.close()
    finishDetach.complete(Unit)

    val failure = assertFailsWith<MapCleanupException> { state.awaitClosed() }
    assertEquals("Map state cleanup failed in 1 resource(s)", failure.message)
    assertEquals("detach failed", failure.cause?.message)
    runtime.close()
  }

  @Test
  fun retained_engine_access_remains_valid_while_the_presentation_detaches() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Demo)
    val finishDetach = CompletableDeferred<Unit>()
    val detachStarted = CompletableDeferred<Unit>()
    val session = BoundLifecycleSession(finishDetach = finishDetach, detachStarted = detachStarted)
    session.lifecycle = state.lifecycle.bind(session)
    session.lifecycle.attach()
    val token = state.reservePresentation()
    state.publishPresentation(token, session)

    state.releasePresentation(token, session)
    detachStarted.await()
    var callbackRan = false

    assertTrue(state.lifecycle.acceptEnginePlatformAccess(session) { callbackRan = true })
    assertTrue(callbackRan)

    finishDetach.complete(Unit)
    testScheduler.runCurrent()
    state.close()
    state.awaitClosed()
    runtime.close()
  }

  @Test
  fun closure_during_presentation_configuration_makes_publication_inert() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Demo)
    val token = state.reservePresentation()
    val adapter = ClosingDuringConfigurationAdapter(state::close)

    state.publishPresentation(token, adapter)

    assertTrue(state.isClosed)
    assertNull(state.currentMapAttachment)
    state.awaitClosed()
    runtime.close()
  }

  @Test
  fun a_style_failure_before_publication_remains_the_durable_load_state() = runTest {
    val runtime = mapRuntimeForTest()
    val state = runtime.createMapState(BaseStyle.Demo)
    val callbacks = state.durableStyleCallbacks()
    val token = state.reservePresentation()
    val adapter = FailureDuringConfigurationAdapter { map ->
      callbacks.onMapFailLoading(map, "style refused")
    }

    state.publishPresentation(token, adapter)

    val failure = assertIs<StyleLoadState.Failed>(state.style.loadState)
    assertEquals("style refused", failure.reason)
    state.close()
    runtime.close()
  }

  @Test
  fun a_configuration_error_publishes_the_presentation_with_failed_style_state() = runTest {
    val runtime = mapRuntimeForTest()
    val state = runtime.createMapState(BaseStyle.Demo)
    val token = state.reservePresentation()
    val adapter = ConfigurationErrorAdapter()

    state.publishPresentation(token, adapter)

    assertSame(adapter, state.currentMapAttachment?.adapter)
    val failure = assertIs<StyleLoadState.Failed>(state.style.loadState)
    assertEquals("style rejected", failure.reason)
    state.close()
    runtime.close()
  }

  @Test
  fun style_events_before_publication_update_the_durable_style_state() = runTest {
    val runtime = mapRuntimeForTest()
    val state = runtime.createMapState(BaseStyle.Demo)
    val token = state.reservePresentation()
    val adapter = PresentationTestAdapter()
    val callbacks = state.durableStyleCallbacks()
    val style = RecordingStyleBinding()

    assertTrue(state.lifecycle.selectAdapterForPresentation(adapter))
    callbacks.onStyleChanged(adapter, style)
    callbacks.onMapFinishedLoading(adapter)
    state.publishPresentation(token, adapter)

    assertEquals(StyleLoadState.Ready, state.style.loadState)
    assertSame(style, state.style.currentLoadedStyle())
    state.close()
    runtime.close()
  }

  @Test
  fun an_accepted_camera_set_updates_the_durable_map_position() {
    val fixture = presentationFixture()
    val position = CameraPosition(target = Position(12.0, 34.0), zoom = 8.0)

    fixture.state.setCameraPosition(position)

    assertEquals(position, fixture.state.cameraPosition)
    assertEquals(position, fixture.adapter.lastCameraPosition)
    fixture.close()
  }

  @Test
  fun camera_intent_accepted_before_detachment_remains_durable() {
    val runtime = mapRuntimeForTest()
    val state = runtime.createMapState(BaseStyle.Demo)
    val token = state.reservePresentation()
    val adapter = ReleasingCameraAdapter { map ->
      state.releasePresentation(token, map)
    }
    state.publishPresentation(token, adapter)
    val presentation = requireNotNull(state.currentMapAttachment)
    val position = CameraPosition(target = Position(12.0, 34.0), zoom = 8.0)
    adapter.releaseOnNextCameraSet = true

    state.setCameraPosition(position)

    assertEquals(position, state.cameraPosition)
    assertFalse(presentation.isValid)
    assertNull(state.currentMapAttachment)
    state.close()
    runtime.close()
  }

  @Test
  fun a_detached_map_keeps_durable_camera_commands_and_rejects_surface_operations() = runTest {
    val fixture = presentationFixture()
    fixture.state.releasePresentation(fixture.token, fixture.adapter)
    val position = CameraPosition(zoom = 4.0)

    fixture.state.setCameraPosition(position)
    assertEquals(position, fixture.state.cameraPosition)
    assertEquals(CameraPosition(), fixture.adapter.lastCameraPosition)
    assertFailsWith<IllegalStateException> {
      fixture.state.queryRenderedFeatures(DpOffset.Zero)
    }
    val viewportReads = fixture.adapter.viewportReads
    fixture.adapter.lastCameraPosition = CameraPosition(zoom = 7.0)
    assertNull(fixture.state.synchronizeCamera(fixture.adapter))
    assertEquals(viewportReads, fixture.adapter.viewportReads)
    assertEquals(position, fixture.state.cameraPosition)
    fixture.close()
  }

  @Test
  fun a_camera_set_while_detached_applies_to_the_next_attachment() {
    val fixture = presentationFixture()
    fixture.state.releasePresentation(fixture.token, fixture.adapter)
    val position = CameraPosition(target = Position(12.0, 34.0), zoom = 8.0)
    fixture.state.setCameraPosition(position)
    val replacement = PresentationTestAdapter()
    val token = fixture.state.reservePresentation()

    fixture.state.publishPresentation(token, replacement)

    assertEquals(position, replacement.lastCameraPosition)
    fixture.close()
  }

  @Test
  fun publishing_into_a_closed_state_is_inert() {
    val fixture = presentationFixture()
    fixture.state.close()

    fixture.state.publishPresentation(fixture.token, fixture.adapter)

    assertTrue(fixture.state.isClosed)
    assertNull(fixture.state.currentMapAttachment)
    fixture.runtime.close()
  }

  @Test
  fun replacement_cleanup_failures_are_reported_on_close() = runTest {
    val runtime = mapRuntimeForTest()
    val state = runtime.createMapState(BaseStyle.Demo)
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
    val failure = assertFailsWith<MapCleanupException> { state.awaitClosed() }
    assertTrue(failure.message.orEmpty().contains("cleanup failed"))
    runtime.close()
  }

  @Test
  fun a_durable_callback_updates_style_load_state_after_the_presentation_leaves() = runTest {
    val runtime = mapRuntimeForTest()
    val state = runtime.createMapState(BaseStyle.Demo)
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
  fun a_durable_source_change_refreshes_sources_after_the_presentation_leaves() = runTest {
    val runtime = mapRuntimeForTest()
    val state = runtime.createMapState(BaseStyle.Demo)
    val token = state.reservePresentation()
    val adapter = RetainedAdapter(failOnClose = false)
    state.publishPresentation(token, adapter)
    val style = RecordingStyleBinding(sources = listOf(attributedVectorSource()))
    val callbacks = state.durableStyleCallbacks()
    callbacks.onStyleChanged(adapter, style)
    callbacks.onMapFinishedLoading(adapter)
    state.releasePresentation(token, adapter)
    testScheduler.advanceUntilIdle()

    style.removeSource("tiles")
    callbacks.onSourceChanged(adapter, "tiles")

    assertTrue(state.style.sources.none())
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

    assertNull(fixture.state.style.sources["points"])
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, firstStyle)
    assertNull(fixture.state.style.sources["points"])
    fixture.state.durableStyleCallbacks().onMapFinishedLoading(fixture.adapter)

    val handle = assertIs<GeoJsonSourceHandle>(fixture.state.style.sources["points"])
    assertNull(fixture.state.style.sources["missing"])
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
  fun a_typed_source_handle_rejects_a_same_id_source_type_replacement() = runTest {
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
    val handle = assertIs<GeoJsonSourceHandle>(fixture.state.style.sources["shared"])
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
  fun a_base_source_handle_does_not_revive_after_same_id_replacement() {
    val fixture = presentationFixture()
    val original = attributedVectorSource("shared", "original")
    val binding = RecordingStyleBinding(sources = listOf(original))
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onMapFinishedLoading(fixture.adapter)
    val stale = checkNotNull(fixture.state.style.sources["shared"])

    assertTrue(fixture.state.style.sources.remove("shared"))
    val replacement =
      fixture.state.style.sources.add(attributedVectorSource("shared", "replacement"))

    assertEquals("replacement", replacement.attributionHtml)
    assertFailsWith<IllegalStateException> { stale.attributionHtml }
    fixture.close()
  }

  @Test
  fun a_declarative_source_handle_does_not_revive_after_same_type_replacement() = runTest {
    val fixture = presentationFixture()
    val original = attributedVectorSource("shared", "original")
    fixture.state.desiredStyleRevision =
      DesiredStyleRevision(listOf(original.definition()), emptyList(), emptyList())
    val binding = RecordingStyleBinding(sources = listOf(original))
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onMapFinishedLoading(fixture.adapter)
    val stale = checkNotNull(fixture.state.style.sources["shared"])
    val replacement = attributedVectorSource("shared", "replacement")

    fixture.state.beginStyleRevision(
      fixture.adapter,
      DesiredStyleRevision(listOf(replacement.definition()), emptyList(), emptyList()),
    )
    binding.replaceSource(replacement)
    fixture.state.markStyleReady(fixture.adapter)

    assertFailsWith<IllegalStateException> { stale.attributionHtml }
    assertEquals("replacement", fixture.state.style.sources["shared"]?.attributionHtml)
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
    val handle = assertIs<GeoJsonSourceHandle>(fixture.state.style.sources["points"])
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
    val state = runtime.createMapState(BaseStyle.Demo)
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
    val handle = assertIs<GeoJsonSourceHandle>(state.style.sources["points"])
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

    val handle = assertIs<LayerHandle>(fixture.state.style.layers["background"])
    assertNull(fixture.state.style.layers["missing"])
    handle.setPaintProperty("background-opacity", JsonPrimitive(0.5))
    assertEquals(JsonPrimitive(0.5), handle.getProperty("background-opacity"))

    loadedStyle.invalidate()
    assertFailsWith<IllegalStateException> { handle.getProperty("background-opacity") }
    fixture.close()
  }

  @Test
  fun a_layer_handle_does_not_revive_after_structural_replacement() = runTest {
    val fixture = presentationFixture()
    val layer = BackgroundLayer("background")
    val original = DesiredStyleLayer(layer.definition(), Anchor.Top, null, null)
    fixture.state.desiredStyleRevision =
      DesiredStyleRevision(emptyList(), listOf(original), emptyList())
    val binding = RecordingStyleBinding(layers = listOf(layer))
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onMapFinishedLoading(fixture.adapter)
    val stale = checkNotNull(fixture.state.style.layers["background"])

    fixture.state.beginStyleRevision(
      fixture.adapter,
      DesiredStyleRevision(emptyList(), listOf(original.copy(anchor = Anchor.Bottom)), emptyList()),
    )
    fixture.state.markStyleReady(fixture.adapter)

    assertFailsWith<IllegalStateException> { stale.getProperty("background-opacity") }
    assertTrue(fixture.state.style.layers["background"] != null)
    fixture.close()
  }

  @Test
  fun publishing_a_replacement_style_makes_handles_unavailable_until_it_is_ready() {
    val fixture = presentationFixture()
    val first = RecordingStyleBinding()
    assertTrue(fixture.state.updateLoadedStyle(fixture.adapter, first))
    assertTrue(fixture.state.markStyleReady(fixture.adapter))
    assertEquals(StyleLoadState.Ready, fixture.state.style.loadState)

    val replacement = RecordingStyleBinding()
    assertTrue(fixture.state.updateLoadedStyle(fixture.adapter, replacement))

    assertEquals(StyleLoadState.Loading, fixture.state.style.loadState)
    assertNull(fixture.state.style.layers["anything"])
    assertTrue(fixture.state.markStyleReady(fixture.adapter))
    assertEquals(StyleLoadState.Ready, fixture.state.style.loadState)
    fixture.close()
  }

  @Test
  fun loaded_style_resource_objects_preserve_engine_order_and_empty_on_invalidation() {
    val fixture = presentationFixture()
    val sources =
      listOf(
        attributedVectorSource("bottom", "first"),
        attributedVectorSource("top", "second"),
      )
    val layers = listOf(BackgroundLayer("bottom"), BackgroundLayer("top"))
    val binding = RecordingStyleBinding(sources = sources, layers = layers)
    val styleSources = fixture.state.style.sources
    val styleLayers = fixture.state.style.layers
    val styleImages = fixture.state.style.images

    assertTrue(styleSources.none())
    assertTrue(styleLayers.none())
    assertFailsWith<IllegalStateException> {
      styleSources.add(attributedVectorSource("unready", "unready"))
    }
    assertFailsWith<IllegalStateException> {
      styleImages.add("unready", FakeImageBitmap(1, 1))
    }
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onMapFinishedLoading(fixture.adapter)

    assertSame(styleSources, fixture.state.style.sources)
    assertSame(styleLayers, fixture.state.style.layers)
    assertSame(styleImages, fixture.state.style.images)
    assertEquals(listOf("bottom", "top"), styleSources.map { it.id })
    assertEquals(listOf("bottom", "top"), styleLayers.map { it.id })

    fixture.state.style.baseStyle = BaseStyle.Json("replacement")
    assertTrue(styleSources.none())
    assertTrue(styleLayers.none())
    fixture.close()
  }

  @Test
  fun imperative_source_commands_publish_results_and_normalize_engine_refusal() {
    val fixture = presentationFixture()
    val binding = RecordingStyleBinding(refusedSourceRemovals = setOf("blocked"))
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onMapFinishedLoading(fixture.adapter)
    val added = attributedVectorSource("added", "added attribution")
    val blocked = attributedVectorSource("blocked", "blocked attribution")

    val firstHandle = fixture.state.style.sources.add(added)
    assertEquals("added", firstHandle.id)
    assertEquals("added", fixture.state.style.sources["added"]?.id)
    fixture.state.style.sources.add(blocked)
    assertFailsWith<StyleHandleException> { fixture.state.style.sources.add(added) }
    assertFalse(fixture.state.style.sources.remove("missing"))
    assertTrue(fixture.state.style.sources.remove("added"))
    assertNull(fixture.state.style.sources["added"])
    val replacementHandle = fixture.state.style.sources.add(added)
    assertFailsWith<IllegalStateException> { firstHandle.attributionHtml }
    assertEquals("added attribution", replacementHandle.attributionHtml)
    assertTrue(fixture.state.style.sources.remove("added"))

    assertFailsWith<StyleHandleException> { fixture.state.style.sources.remove("blocked") }
    assertTrue("blocked" in binding.sources)
    assertTrue(fixture.state.style.sources["blocked"] != null)
    fixture.close()
  }

  @Test
  fun imperative_image_commands_add_remove_and_reject_duplicates() {
    val fixture = presentationFixture()
    val binding = RecordingStyleBinding()
    val image = FakeImageBitmap(1, 1)
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onMapFinishedLoading(fixture.adapter)

    fixture.state.style.images.add("marker", image)
    assertEquals(setOf("marker"), binding.imageIds)
    assertFailsWith<StyleHandleException> { fixture.state.style.images.add("marker", image) }
    assertFalse(fixture.state.style.images.remove("missing"))
    assertTrue(fixture.state.style.images.remove("marker"))
    assertTrue(binding.imageIds.isEmpty())
    fixture.close()
  }

  @Test
  fun transition_light_sky_and_projection_commands_target_only_a_ready_loaded_style() {
    val fixture = presentationFixture()
    val binding = RecordingStyleBinding(refusedLightProperties = setOf("position"))
    val transition = fixture.state.style.transition
    val light = fixture.state.style.light
    val sky = fixture.state.style.sky
    val projection = fixture.state.style.projection
    val options = TransitionOptions(duration = 1.seconds, delay = 20.milliseconds)

    assertFailsWith<IllegalArgumentException> { TransitionOptions(duration = Duration.INFINITE) }
    assertFailsWith<IllegalArgumentException> { TransitionOptions(delay = (-1).milliseconds) }
    assertNull(transition.get())
    assertNull(transition.placementTransitions())
    assertNull(light.getProperty("color"))
    assertNull(sky.getProperty("sky-color"))
    assertNull(projection.getProperty("type"))
    assertFailsWith<IllegalStateException> { transition.set(options) }
    assertFailsWith<IllegalStateException> { transition.setPlacementTransitions(false) }
    assertFailsWith<IllegalStateException> { light.set(Light()) }
    assertFailsWith<IllegalStateException> { sky.set(Sky()) }
    assertFailsWith<IllegalStateException> { projection.set(Projection()) }
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onMapFinishedLoading(fixture.adapter)

    assertEquals(TransitionOptions(), transition.get())
    transition.set(options)
    assertEquals(options, transition.get())
    assertEquals(options, binding.transition)
    transition.setPlacementTransitions(false)
    assertEquals(false, transition.placementTransitions())

    light.set(Light(position = nil(), intensity = const(0.25f)))
    assertEquals(JsonPrimitive(0.25f), light.getProperty("intensity"))
    light.set(Light(position = nil(), intensity = nil()))
    assertNull(light.getProperty("intensity"))
    assertEquals(JsonPrimitive("viewport"), light.getProperty("anchor"))
    assertFailsWith<StyleHandleException> { light.set(Light()) }
    assertNull(light.getProperty("intensity"))

    sky.set(Sky(atmosphereBlend = const(0f)))
    assertEquals(JsonPrimitive(0f), sky.getProperty("atmosphere-blend"))
    sky.set(null)
    assertNull(sky.getProperty("atmosphere-blend"))

    projection.set(Projection(type = const(ProjectionType.Globe)))
    assertEquals(JsonPrimitive("globe"), projection.getProperty("type"))
    projection.set(
      Projection(
        type =
          const(
            ProjectionTransition(ProjectionType.VerticalPerspective, ProjectionType.Mercator, 0.5f)
          )
      )
    )
    assertEquals(
      JsonArray(
        listOf(
          JsonPrimitive("vertical-perspective"),
          JsonPrimitive("mercator"),
          JsonPrimitive(0.5f),
        )
      ),
      projection.getProperty("type"),
    )

    fixture.state.style.baseStyle = BaseStyle.Json("replacement")
    assertNull(transition.get())
    assertNull(light.getProperty("color"))
    assertNull(sky.getProperty("sky-color"))
    assertNull(projection.getProperty("type"))
    assertFailsWith<IllegalStateException> { transition.set(options) }
    fixture.close()
  }

  @Test
  fun imperative_commands_cannot_mutate_composition_owned_resources() {
    val fixture = presentationFixture()
    val source = attributedVectorSource("owned", "owned attribution")
    val image = FakeImageBitmap(1, 1)
    fixture.state.desiredStyleRevision =
      DesiredStyleRevision(
        sources = listOf(source.definition()),
        layers = emptyList(),
        images =
          listOf(
            StyleImageDefinition(
              "owned",
              ImageSnapshot.capture(image),
              sdf = false,
              stretch = null,
            )
          ),
      )
    val binding = RecordingStyleBinding(images = listOf("owned" to image), sources = listOf(source))
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onMapFinishedLoading(fixture.adapter)

    assertFailsWith<StyleHandleException> { fixture.state.style.sources.remove("owned") }
    assertFailsWith<StyleHandleException> { fixture.state.style.images.remove("owned") }
    assertTrue(binding.sourceExists("owned") == true)
    assertTrue(binding.imageExists("owned") == true)
    fixture.close()
  }

  @Test
  fun a_declarative_revision_cannot_claim_an_imperative_resource_id() = runTest {
    val fixture = presentationFixture()
    val binding = RecordingStyleBinding()
    val source = attributedVectorSource("shared", "shared attribution")
    val image = FakeImageBitmap(1, 1)
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onMapFinishedLoading(fixture.adapter)
    fixture.state.style.sources.add(source)

    assertFailsWith<StyleHandleException> {
      fixture.state.beginStyleRevision(
        fixture.adapter,
        DesiredStyleRevision(
          sources = listOf(source.definition()),
          layers = emptyList(),
          images = emptyList(),
        ),
      )
    }
    assertEquals(StyleLoadState.Ready, fixture.state.style.loadState)
    assertTrue(fixture.state.style.sources["shared"] != null)

    assertTrue(fixture.state.style.sources.remove("shared"))
    fixture.state.style.images.add("shared", image)
    assertFailsWith<StyleHandleException> {
      fixture.state.beginStyleRevision(
        fixture.adapter,
        DesiredStyleRevision(
          sources = emptyList(),
          layers = emptyList(),
          images =
            listOf(
              StyleImageDefinition(
                "shared",
                ImageSnapshot.capture(image),
                sdf = false,
                stretch = null,
              )
            ),
        ),
      )
    }
    assertEquals(StyleLoadState.Ready, fixture.state.style.loadState)
    assertTrue(binding.imageExists("shared") == true)
    fixture.close()
  }

  @Test
  fun a_nested_imperative_command_cannot_cross_a_resource_reservation() {
    val fixture = presentationFixture()
    val image = FakeImageBitmap(1, 1)
    var nestedFailure: Throwable? = null
    val binding =
      RecordingStyleBinding(
        beforeAddImage = {
          nestedFailure =
            runCatching { fixture.state.style.images.add("shared", image) }.exceptionOrNull()
        }
      )
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onMapFinishedLoading(fixture.adapter)

    fixture.state.style.images.add("shared", image)

    assertIs<StyleHandleException>(nestedFailure)
    assertEquals(StyleLoadState.Ready, fixture.state.style.loadState)
    assertTrue(binding.imageExists("shared") == true)
    fixture.close()
  }

  @Test
  fun attribution_derives_from_base_declarative_and_imperative_sources() {
    val fixture = presentationFixture()
    val base = attributedVectorSource("base", "base attribution")
    val declarative = attributedVectorSource("declarative", "declarative attribution")
    fixture.state.desiredStyleRevision =
      DesiredStyleRevision(
        sources = listOf(declarative.definition()),
        layers = emptyList(),
        images = emptyList(),
      )
    val binding = RecordingStyleBinding(sources = listOf(base, declarative))
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onMapFinishedLoading(fixture.adapter)

    fixture.state.style.sources.add(attributedVectorSource("imperative", "imperative attribution"))

    assertEquals(
      listOf("base attribution", "declarative attribution", "imperative attribution"),
      fixture.state.style.attributions(),
    )
    fixture.close()
  }

  @Test
  fun attributions_observed_before_the_style_is_ready_update_once_it_loads() {
    val fixture = presentationFixture()
    val attributions = derivedStateOf { fixture.state.style.attributions() }
    assertEquals(emptyList(), attributions.value)

    val binding =
      RecordingStyleBinding(sources = listOf(attributedVectorSource("base", "base attribution")))
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onMapFinishedLoading(fixture.adapter)

    assertEquals(listOf("base attribution"), attributions.value)
    fixture.close()
  }

  @Test
  fun imperative_resources_survive_detachment_only_with_the_retained_generation() = runTest {
    val runtime = mapRuntimeForTest()
    val state = runtime.createMapState(BaseStyle.Empty)
    val token = state.reservePresentation()
    val adapter = RetainedAdapter(failOnClose = false)
    val binding = RecordingStyleBinding()
    state.publishPresentation(token, adapter)
    state.durableStyleCallbacks().onStyleChanged(adapter, binding)
    state.durableStyleCallbacks().onMapFinishedLoading(adapter)
    state.style.sources.add(attributedVectorSource("retained", "retained attribution"))

    state.releasePresentation(token, adapter)
    testScheduler.advanceUntilIdle()

    assertTrue(state.style.sources["retained"] != null)
    assertTrue(state.style.sources.remove("retained"))

    val replacement = RecordingStyleBinding()
    val replacementToken = state.reservePresentation()
    val replacementAdapter = RetainedAdapter(failOnClose = false)
    state.publishPresentation(replacementToken, replacementAdapter)
    state.durableStyleCallbacks().onStyleChanged(replacementAdapter, replacement)
    state.durableStyleCallbacks().onMapFinishedLoading(replacementAdapter)
    assertTrue(state.style.sources.none())
    state.close()
    runtime.close()
  }

  @Test
  fun publication_happens_after_the_adapter_accepts_initial_map_state() {
    val runtime = mapRuntimeForTest()
    val initialCamera = CameraPosition(target = Position(12.0, 34.0), zoom = 8.0)
    val state =
      runtime.createMapState(
        baseStyle = BaseStyle.Demo,
        initialCameraPosition = initialCamera,
      )
    val token = state.reservePresentation()
    val adapter = PresentationTestAdapter { state.currentMapAttachment }

    state.publishPresentation(token, adapter)

    assertFalse(adapter.presentationWasVisibleWhileConfiguring)
    assertEquals(initialCamera, adapter.lastCameraPosition)
    assertTrue(state.currentMapAttachment != null)
    state.close()
    runtime.close()
  }

  @Test
  fun viewport_observations_are_null_before_the_first_viewport() {
    val fixture = presentationFixture()

    assertNull(fixture.state.getVisibleRegion())
    assertNull(fixture.state.getVisibleBoundingBox())
    assertNull(fixture.state.metersPerDpAtLatitude(0.0))
    fixture.close()
  }

  @Test
  fun publication_uses_the_viewport_the_adapter_has_for_the_current_attachment() {
    val runtime = mapRuntimeForTest()
    val state = runtime.createMapState(BaseStyle.Demo)
    val token = state.reservePresentation()
    val viewport = testViewport()
    val adapter = PresentationTestAdapter().apply { currentViewport = viewport }

    state.publishPresentation(token, adapter)

    assertEquals(viewport, state.viewport)
    state.close()
    runtime.close()
  }

  @Test
  fun await_viewport_waits_for_the_next_attachment() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Demo)
    val viewport = testViewport()
    val waiting = async { state.awaitViewport() }
    testScheduler.runCurrent()

    assertFalse(waiting.isCompleted)
    val token = state.reservePresentation()
    state.publishPresentation(
      token,
      PresentationTestAdapter().apply { currentViewport = viewport },
    )

    assertEquals(viewport, waiting.await())
    state.close()
    state.awaitClosed()
    runtime.close()
  }

  @Test
  fun a_viewport_that_arrives_after_publication_still_seeds_the_presentation() {
    val runtime = mapRuntimeForTest()
    val state = runtime.createMapState(BaseStyle.Demo)
    val token = state.reservePresentation()
    val adapter = PresentationTestAdapter()

    state.publishPresentation(token, adapter)
    assertNull(state.currentMapAttachment?.viewport)

    val viewport = testViewport()
    adapter.currentViewport = viewport
    state.lifecycle.seedCurrentPresentationViewport(adapter)

    assertEquals(viewport, state.viewport)
    state.close()
    runtime.close()
  }

  @Test
  fun a_bounds_fit_waits_for_an_attachment_and_its_viewport() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Demo)
    val operation = async {
      state.fitCameraToBounds(BoundingBox(Position(-1.0, -1.0), Position(1.0, 1.0)))
    }
    testScheduler.runCurrent()
    assertFalse(operation.isCompleted)

    val token = state.reservePresentation()
    val adapter = PresentationTestAdapter()
    state.publishPresentation(token, adapter)
    testScheduler.runCurrent()
    assertFalse(operation.isCompleted)

    requireNotNull(state.currentMapAttachment).updateViewport(testViewport())
    operation.await()
    assertTrue(adapter.boundsFit.isCompleted)
    state.close()
    state.awaitClosed()
    runtime.close()
  }

  @Test
  fun a_rendered_query_waits_for_the_first_viewport() = runTest {
    val fixture = presentationFixture()
    supervisorScope {
      val query = async { fixture.state.queryRenderedFeatures(DpOffset.Zero) }
      testScheduler.runCurrent()

      assertFalse(fixture.adapter.queryStarted.isCompleted)

      fixture.attachment.updateViewport(testViewport())
      fixture.adapter.queryStarted.await()
      fixture.state.releasePresentation(fixture.token, fixture.adapter)

      assertFailsWith<CancellationException> { query.await() }
    }
    fixture.close()
  }

  @Test
  fun detachment_fails_an_active_query_instead_of_targeting_another_presentation() = runTest {
    val fixture = presentationFixture()
    fixture.attachment.updateViewport(testViewport())
    supervisorScope {
      val query = async { fixture.state.queryRenderedFeatures(DpOffset.Zero) }
      fixture.adapter.queryStarted.await()

      fixture.state.releasePresentation(fixture.token, fixture.adapter)

      assertFailsWith<CancellationException> { query.await() }
    }
    fixture.close()
  }

  @Test
  fun a_replacement_animation_cancels_only_the_previous_camera_mutation() = runTest {
    val fixture = presentationFixture()
    val first = async {
      fixture.state.animateCameraPosition(CameraPosition(zoom = 2.0), 1.seconds)
    }
    fixture.adapter.animationStarted.await()
    val second = async {
      fixture.state.animateCameraPosition(CameraPosition(zoom = 3.0), 1.seconds)
    }
    testScheduler.runCurrent()

    assertTrue(first.isCancelled)
    assertFalse(second.isCompleted)
    assertTrue(fixture.attachment.isValid)

    fixture.adapter.finishAnimation.complete(Unit)
    second.await()
    fixture.close()
  }

  @Test
  fun the_latest_camera_animation_waits_for_attachment_and_restarts_on_replacement() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Demo)
    val superseded = async {
      state.animateCameraPosition(CameraPosition(zoom = 2.0), 1.seconds)
    }
    testScheduler.runCurrent()
    val animation = async {
      state.animateCameraPosition(CameraPosition(zoom = 4.0), 1.seconds)
    }
    testScheduler.runCurrent()
    assertTrue(superseded.isCancelled)
    assertFalse(animation.isCompleted)

    val firstToken = state.reservePresentation()
    val first = PresentationTestAdapter()
    state.publishPresentation(firstToken, first)
    first.animationStarted.await()

    state.releasePresentation(firstToken, first)
    testScheduler.runCurrent()
    assertFalse(animation.isCompleted)

    val replacementToken = state.reservePresentation()
    val replacement = PresentationTestAdapter()
    state.publishPresentation(replacementToken, replacement)
    replacement.animationStarted.await()
    replacement.finishAnimation.complete(Unit)

    animation.await()
    state.close()
    state.awaitClosed()
    runtime.close()
  }

  @Test
  fun closing_a_map_fails_a_camera_animation_waiting_for_attachment() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Demo)
    supervisorScope {
      val animation = async {
        state.animateCameraPosition(CameraPosition(zoom = 4.0), 1.seconds)
      }
      testScheduler.runCurrent()

      state.close()

      assertFailsWith<CancellationException> { animation.await() }
    }
    state.awaitClosed()
    runtime.close()
  }
}

private fun attributedVectorSource(): VectorSource = attributedVectorSource("tiles", "attribution")

private fun attributedVectorSource(id: String, attribution: String): VectorSource =
  VectorSource(
    id = id,
    tiles = listOf("https://example.com/{z}/{x}/{y}.pbf"),
    options = TileSetOptions(attributionHtml = attribution),
  )

private data class PresentationFixture(
  val runtime: MapRuntime,
  val state: MapState,
  val token: MapPresentationToken,
  val adapter: PresentationTestAdapter,
  val attachment: MapAttachment,
) {
  fun close() {
    state.close()
    runtime.close()
  }
}

private class FakeImageBitmap(override val width: Int, override val height: Int) : ImageBitmap {
  override val colorSpace: ColorSpace = ColorSpaces.Srgb
  override val hasAlpha: Boolean = true
  override val config: ImageBitmapConfig = ImageBitmapConfig.Argb8888

  override fun readPixels(
    buffer: IntArray,
    startX: Int,
    startY: Int,
    width: Int,
    height: Int,
    bufferOffset: Int,
    stride: Int,
  ) = Unit

  override fun prepareToDraw() = Unit
}

private fun presentationFixture(): PresentationFixture {
  val runtime = mapRuntimeForTest()
  val state = runtime.createMapState(BaseStyle.Demo)
  val token = state.reservePresentation()
  val adapter = PresentationTestAdapter()
  state.publishPresentation(token, adapter)
  return PresentationFixture(
    runtime,
    state,
    token,
    adapter,
    requireNotNull(state.currentMapAttachment),
  )
}

private class RetainedAdapter(private val failOnClose: Boolean) : PresentationTestAdapter() {
  override val retainsEngineBetweenPresentations: Boolean = true

  override suspend fun detachPresentation() = Unit

  override suspend fun awaitClosed() {
    if (failOnClose) error("cleanup failed")
  }
}

private class BlockingDetachAdapter(private val failOnDetach: Boolean = false) :
  PresentationTestAdapter() {
  val detachStarted = CompletableDeferred<Unit>()
  val finishDetach = CompletableDeferred<Unit>()

  override suspend fun detachPresentation() {
    detachStarted.complete(Unit)
    finishDetach.await()
    if (failOnDetach) error("detach failed")
  }
}

private class BoundLifecycleSession(
  private val failOnClose: Boolean = false,
  private val finishCleanup: CompletableDeferred<Unit>? = null,
  private val failOnDetach: Boolean = false,
  private val finishDetach: CompletableDeferred<Unit>? = null,
  private val detachStarted: CompletableDeferred<Unit>? = null,
) : PresentationTestAdapter(), MapLifecycleSession {
  lateinit var lifecycle: MapLifecycleBinding
  val commands = mutableListOf<String>()

  override val retainsEngineBetweenPresentations = true
  override val presentationCompatibilityKey: Any = Any()

  override val engineRetention: EngineRetention = EngineRetention.RETAIN

  override suspend fun createEngine(identity: EngineMapIdentity) {
    commands += "create"
  }

  override suspend fun attach(identity: EngineMapIdentity, lease: RenderLease) {
    commands += "attach"
  }

  override suspend fun detach(identity: EngineMapIdentity, lease: RenderLease) {
    commands += "detach"
    detachStarted?.complete(Unit)
    finishDetach?.await()
    if (failOnDetach) error("detach failed")
  }

  override suspend fun destroyEngine(identity: EngineMapIdentity) {
    commands += "destroy"
  }

  override suspend fun closeResources() {
    commands += "close resources"
    finishCleanup?.await()
    if (failOnClose) error("bound session cleanup failed")
  }

  override suspend fun detachPresentation() {
    lifecycle.detachCurrentPresentation()
  }

  override fun close() = lifecycle.close()

  override suspend fun awaitClosed() = lifecycle.awaitClosed()
}

private class ClosingDuringConfigurationAdapter(private val closeState: () -> Unit) :
  PresentationTestAdapter() {
  private var closed = false

  override fun setCameraPosition(cameraPosition: CameraPosition) {
    super.setCameraPosition(cameraPosition)
    if (!closed) {
      closed = true
      closeState()
    }
  }
}

private class FailureDuringConfigurationAdapter(private val reportFailure: (MapAdapter) -> Unit) :
  PresentationTestAdapter() {
  override fun setBaseStyle(style: BaseStyle) {
    super.setBaseStyle(style)
    reportFailure(this)
  }
}

private class ConfigurationErrorAdapter : PresentationTestAdapter() {
  override fun setBaseStyle(style: BaseStyle) {
    error("style rejected")
  }
}

private class ReleasingCameraAdapter(private val release: (MapAdapter) -> Unit) :
  PresentationTestAdapter() {
  var releaseOnNextCameraSet = false

  override fun setCameraPosition(cameraPosition: CameraPosition) {
    super.setCameraPosition(cameraPosition)
    if (releaseOnNextCameraSet) {
      releaseOnNextCameraSet = false
      release(this)
    }
  }
}

internal open class PresentationTestAdapter(
  private val currentAttachment: () -> MapAttachment? = { null }
) : MapAdapter {
  var lastCameraPosition = CameraPosition()
  var presentationWasVisibleWhileConfiguring = false
  var viewportReads = 0
  var currentViewport: Viewport? = null
  val boundsFit = CompletableDeferred<Unit>()
  val queryStarted = CompletableDeferred<Unit>()
  val animationStarted = CompletableDeferred<Unit>()
  val finishAnimation = CompletableDeferred<Unit>()

  open override fun close() = Unit

  open override suspend fun awaitClosed() = Unit

  override suspend fun animateCameraPosition(finalPosition: CameraPosition, duration: Duration) {
    animationStarted.complete(Unit)
    finishAnimation.await()
  }

  override suspend fun animateCameraToBounds(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
    duration: Duration,
  ) = awaitCancellation()

  override fun setBaseStyle(style: BaseStyle) {
    presentationWasVisibleWhileConfiguring =
      presentationWasVisibleWhileConfiguring || currentAttachment() != null
  }

  override suspend fun reconcileStyleRevision(revision: DesiredStyleRevision): Boolean = true

  override suspend fun replayStyleRevision(revision: DesiredStyleRevision) = Unit

  override fun getCameraPosition(): CameraPosition = lastCameraPosition

  override fun setCameraPosition(cameraPosition: CameraPosition) {
    presentationWasVisibleWhileConfiguring =
      presentationWasVisibleWhileConfiguring || currentAttachment() != null
    lastCameraPosition = cameraPosition
  }

  override fun setCameraPadding(padding: PaddingValues) = Unit

  override fun fitCameraToBounds(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
  ) {
    boundsFit.complete(Unit)
  }

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
    return currentViewport
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
