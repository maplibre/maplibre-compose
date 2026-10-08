package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.maplibre.compose.camera.CameraAnchor
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.camera.internal.CameraCommandGuard
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.ast.compile
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.LayerHandle
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.layers.asLayerProperty
import org.maplibre.compose.overlay.attributions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.GeoJsonSourceHandle
import org.maplibre.compose.sources.ImageSource
import org.maplibre.compose.sources.VectorTileSource
import org.maplibre.compose.sources.VectorTileSourceHandle
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.Light
import org.maplibre.compose.style.Projection
import org.maplibre.compose.style.QueuedOwnerStyleBinding
import org.maplibre.compose.style.RecordingStyleBinding
import org.maplibre.compose.style.Sky
import org.maplibre.compose.style.SourceDefinition
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleHandleException
import org.maplibre.compose.style.StyleImageDefinition
import org.maplibre.compose.style.StyleMutationException
import org.maplibre.compose.style.StyleReconciler
import org.maplibre.compose.style.StyleSnapshot
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.testing.setImage
import org.maplibre.compose.util.DpPadding
import org.maplibre.compose.util.PositionQuad
import org.maplibre.compose.util.PreparedImage
import org.maplibre.compose.util.VisibleBounds
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.GeometryCollection
import org.maplibre.spatialk.geojson.MultiPoint
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection

@OptIn(ExperimentalCoroutinesApi::class)
class MapPresentationTest {

  @Test
  fun replacement_presentation_keeps_handles_from_suspended_publication() = runTest {
    val binding = QueuedOwnerStyleBinding(RecordingStyleBinding())
    val reconciler = StyleReconciler()
    val adapter =
      object : PresentationTestAdapter() {
        override val retainsEngineBetweenPresentations = true

        override suspend fun <T> reconcileStyleRevision(
          revision: StyleSnapshot,
          capture: (StyleBinding) -> T,
        ): T =
          checkNotNull(
            binding.awaitOwner {
              reconciler.apply(binding, revision)
              capture(binding)
            }
          )

        override suspend fun detachPresentation() = Unit
      }
    val runtime =
      mapRuntimeForTest(physicalScope = backgroundScope, mainDispatcher = testMainDispatcher())
    val state = runtime.createMapState(BaseStyle.Empty)
    try {
      val token = state.reservePresentation()
      state.publishPresentation(token, adapter)
      state.styleAuthority.updateLoadedStyle(adapter, binding)
      state.styleAuthority.markStyleReady(adapter)
      val revision =
        StyleSnapshot(
          listOf(attributedVectorSource("committed-source", "attribution").definition()),
          listOf(
            StyleSnapshot.Layer(
              TestLayer("committed", "background").definition(),
              Anchor.Top,
              null,
              null,
            )
          ),
          emptyList(),
        )
      // The owner is busy, so the publication's source read waits in its queue.
      binding.ownerBusy = true
      val old =
        launch(start = CoroutineStart.UNDISPATCHED) {
          state.styleAuthority.applyStyleRevision(adapter, binding, revision)
        }
      assertTrue(binding.layerIds().isEmpty())
      assertNull(state.style.layers["committed"])
      // Disposal cancels the old composition, but its accepted publication must finish.
      old.cancel()
      binding.ownerBusy = false
      state.releasePresentation(token, adapter)
      val replacement = state.reservePresentation()
      state.publishPresentation(replacement, adapter)
      state.styleAuthority.updateLoadedStyle(adapter, binding)
      // The replacement retains the engine and emits no layer delta for the same revision.
      val next =
        launch(start = CoroutineStart.UNDISPATCHED) {
          state.styleAuthority.applyStyleRevision(adapter, binding, revision)
        }
      binding.runOwnerTasks()
      old.join()
      next.join()
      assertNotNull(state.style.sources["committed-source"])
      assertNotNull(state.style.layers["committed"], "the native layer must retain a public handle")
    } finally {
      state.close()
      runtime.close()
    }
  }

  @Test
  fun waiting_revisions_can_cancel_and_cannot_claim_a_replacement_style() = runTest {
    val finishCommit = CompletableDeferred<Unit>()
    var commits = 0
    var fixtureBinding: StyleBinding? = null
    val adapter =
      object : PresentationTestAdapter() {
        override suspend fun <T> reconcileStyleRevision(
          revision: StyleSnapshot,
          capture: (StyleBinding) -> T,
        ): T {
          commits++
          finishCommit.await()
          return capture(checkNotNull(fixtureBinding))
        }
      }
    val fixture = presentationFixture(adapter)
    try {
      val old = RecordingStyleBinding()
      val replacement = RecordingStyleBinding()
      fixtureBinding = old
      val authority = fixture.state.styleAuthority
      authority.updateLoadedStyle(adapter, old)
      val revision =
        StyleSnapshot(
          sources = listOf(attributedVectorSource("stale", "old").definition()),
          layers = emptyList(),
          images = emptyList(),
        )
      val accepted =
        launch(start = CoroutineStart.UNDISPATCHED) {
          authority.applyStyleRevision(adapter, old, revision)
        }
      val cancelled =
        launch(start = CoroutineStart.UNDISPATCHED) {
          authority.applyStyleRevision(adapter, old, revision)
        }
      assertEquals(1, commits, "the waiting revision must not reach the engine")
      cancelled.cancel()
      cancelled.join()
      val stale =
        launch(start = CoroutineStart.UNDISPATCHED) {
          authority.applyStyleRevision(adapter, old, revision)
        }
      authority.updateLoadedStyle(adapter, replacement)
      finishCommit.complete(Unit)
      accepted.join()
      stale.join()
      assertEquals(1, commits, "cancelled or stale waiting revisions must not reach the engine")
      assertEquals(StyleSnapshot.Empty, fixture.state.style.declaredRevision)
      assertTrue(replacement.sourceIds().isEmpty())
    } finally {
      finishCommit.complete(Unit)
      fixture.close()
    }
  }

  @Test
  fun property_and_data_revisions_preserve_readiness_handles_and_resource_lists() = runTest {
    val fixture = presentationFixture()
    try {
      val source =
        GeoJsonSource(
          "puck",
          GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[]}"""),
        )
      val layer = TestLayer("animated", "background")
      val original =
        StyleSnapshot(
          listOf(source.definition()),
          listOf(StyleSnapshot.Layer(layer.definition(), Anchor.Top, null, null)),
          emptyList(),
        )
      val backing =
        RecordingStyleBinding(sources = listOf(attributedVectorSource("base", "Map attribution")))
      val binding = backing
      fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
      fixture.applyRevision(binding, original)
      fixture.state.styleAuthority.markStyleReady(fixture.adapter)
      val sourceHandle = checkNotNull(fixture.state.style.sources["puck"])
      val layerHandle = checkNotNull(fixture.state.style.layers["animated"])

      for (opacity in listOf(0.25, 0.5, 0.75)) {
        val updatedSource =
          GeoJsonSource(
            source.id,
            GeoJsonData.JsonString(
              """{"type":"Feature","geometry":{"type":"Point","coordinates":[0,0]},"properties":{"opacity":$opacity}}"""
            ),
          )
        val definition =
          layer.definition().let {
            it.copy(
              value =
                JsonObject(
                  it.value +
                    ("paint" to
                      buildJsonObject {
                        put("background-opacity", opacity)
                      })
                )
            )
          }
        val revision =
          StyleSnapshot(
            listOf(updatedSource.definition()),
            listOf(StyleSnapshot.Layer(definition, Anchor.Top, null, null)),
            emptyList(),
          )
        assertEquals(StyleLoadState.Ready, fixture.state.style.loadState)
        assertEquals(listOf("Map attribution"), fixture.state.style.attributions())
        fixture.applyRevision(binding, revision)
        assertSame(sourceHandle, fixture.state.style.sources["puck"])
        assertSame(layerHandle, fixture.state.style.layers["animated"])
        assertEquals(JsonPrimitive(opacity), layerHandle.getProperty("background-opacity"))
      }

      fixture.state.styleAuthority.markStyleFailed(fixture.adapter, "revision failed")
      fixture.applyRevision(binding, fixture.state.style.declaredRevision)
      assertEquals(StyleLoadState.Loading, fixture.state.style.loadState)
      assertNull(fixture.state.style.layers["animated"])
      fixture.state.styleAuthority.markStyleReady(fixture.adapter)
      assertEquals(StyleLoadState.Ready, fixture.state.style.loadState)
    } finally {
      fixture.close()
    }
  }

  @Test
  fun a_suspended_source_query_depends_on_its_source_identity_not_the_revision() = runTest {
    for (replaceSource in listOf(false, true)) {
      val fixture = presentationFixture()
      try {
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<Double>()
        val binding =
          object : StyleBinding by RecordingStyleBinding() {
            override suspend fun clusterExpansionZoom(
              sourceId: String,
              feature: Feature<*, JsonObject?>,
            ): Double {
              started.complete(Unit)
              return result.await()
            }
          }
        val source =
          GeoJsonSource(
            "points",
            GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[]}"""),
          )
        val layer = TestLayer("background", "background")
        val original =
          StyleSnapshot(
            listOf(source.definition()),
            listOf(StyleSnapshot.Layer(layer.definition(), Anchor.Top, null, null)),
            emptyList(),
          )
        fixture.state.styleAuthority.updateLoadedStyle(fixture.adapter, binding)
        fixture.applyRevision(binding, original)
        fixture.state.styleAuthority.markStyleReady(fixture.adapter)
        val handle = assertIs<GeoJsonSourceHandle>(fixture.state.style.sources["points"])
        val feature = Feature(Point(Position(0.0, 0.0)), buildJsonObject { put("cluster_id", 1) })
        val query = async { runCatching { handle.getClusterExpansionZoom(feature) } }
        started.await()
        layer.paint(
          "background-opacity",
          (const(0.5f).compile(ExpressionContext.None)).asLayerProperty(),
        )
        val next =
          StyleSnapshot(
            if (replaceSource)
              listOf(
                GeoJsonSource(
                    "points",
                    GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[]}"""),
                  ) {
                    cluster = true
                  }
                  .definition()
              )
            else original.sources,
            listOf(StyleSnapshot.Layer(layer.definition(), Anchor.Top, null, null)),
            emptyList(),
          )
        fixture.applyRevision(binding, next)
        result.complete(4.0)
        val outcome = query.await()
        if (replaceSource) assertNull(outcome.getOrThrow())
        else assertEquals(4.0, outcome.getOrThrow())
      } finally {
        fixture.close()
      }
    }
  }

  @Test
  fun a_named_source_event_updates_attribution_and_preserves_unchanged_handles() = runTest {
    val fixture = presentationFixture()
    try {
      val backing =
        RecordingStyleBinding(
          sources =
            listOf(
              attributedVectorSource("first", "initial"),
              attributedVectorSource("second", "unchanged"),
            )
        )
      val binding = backing
      fixture.state.styleAuthority.updateLoadedStyle(fixture.adapter, binding)
      fixture.state.styleAuthority.markStyleReady(fixture.adapter)
      val second = fixture.state.style.sources["second"]
      backing.replaceSource(attributedVectorSource("first", "updated"))
      fixture.state.durableStyleCallbacks().onStyleSourcesChanged(fixture.adapter)
      assertSame(second, fixture.state.style.sources["second"])
      assertEquals("updated", fixture.state.style.sources["first"]?.attributionHtml)
      assertEquals(StyleLoadState.Ready, fixture.state.style.loadState)
    } finally {
      fixture.close()
    }
  }

  @Test
  fun resource_edits_update_the_catalog_without_restarting_loading() = runTest {
    val fixture = presentationFixture()
    try {
      val binding = RecordingStyleBinding(layers = listOf(TestLayer("base", "background")))
      fixture.state.styleAuthority.updateLoadedStyle(fixture.adapter, binding)
      fixture.state.styleAuthority.markStyleReady(fixture.adapter)
      val base = checkNotNull(fixture.state.style.layers["base"])
      suspend fun apply(ids: List<String>) {
        val revision =
          StyleSnapshot(
            if (ids.isEmpty()) emptyList()
            else listOf(attributedVectorSource("added", "attribution").definition()),
            ids.map { id ->
              StyleSnapshot.Layer(TestLayer(id, "background").definition(), Anchor.Top, null, null)
            },
            emptyList(),
          )
        assertEquals(StyleLoadState.Ready, fixture.state.style.loadState)
        fixture.applyRevision(binding, revision)
        assertEquals(StyleLoadState.Ready, fixture.state.style.loadState)
      }
      apply(listOf("a", "b"))
      val a = checkNotNull(fixture.state.style.layers["a"])
      assertEquals(listOf("base", "a", "b"), fixture.state.style.layers.map { it.id })
      assertEquals("background", a.type)
      assertEquals("attribution", fixture.state.style.sources["added"]?.attributionHtml)
      apply(listOf("b", "a"))
      assertEquals(listOf("base", "b", "a"), fixture.state.style.layers.map { it.id })
      assertSame(a, fixture.state.style.layers["a"])
      assertSame(base, fixture.state.style.layers["base"])
      apply(emptyList())
      assertEquals(listOf("base"), fixture.state.style.layers.map { it.id })
      assertTrue(fixture.state.style.sources.none())
      assertSame(base, fixture.state.style.layers["base"])
      assertNull(a.getProperty("background-opacity"))
    } finally {
      fixture.close()
    }
  }

  @Test
  fun an_unadopted_session_is_independent_of_map_closure() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Empty)
    val abandoned = BoundLifecycleSession()
    abandoned.lifecycle = state.lifecycle.createRetainedEngineLifecycle(abandoned, abandoned)
    val failedCandidate = BoundLifecycleSession(failOnClose = true)
    failedCandidate.lifecycle =
      state.lifecycle.createRetainedEngineLifecycle(failedCandidate, failedCandidate)
    failedCandidate.close()
    assertFailsWith<MapCleanupException> { failedCandidate.awaitClosed() }
    state.publishPresentation(state.reservePresentation(), failedCandidate)
    assertNull(state.currentMapAttachment)
    assertFailsWith<MapClosedException> {
      state.lifecycle.retainAdapterForPlatformAccess { failedCandidate }
    }

    state.close()
    state.awaitClosed()
    assertTrue(abandoned.commands.isEmpty())
    abandoned.close()
    abandoned.awaitClosed()
    assertEquals(listOf("close resources"), abandoned.commands)
    runtime.close()
  }

  @Test
  fun closing_map_state_closes_a_bound_session_before_it_is_published() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Demo)
    val session = BoundLifecycleSession()
    session.lifecycle = state.lifecycle.createRetainedEngineLifecycle(session, session)
    state.lifecycle.adopt(session)
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
    session.lifecycle = state.lifecycle.createRetainedEngineLifecycle(session, session)
    state.lifecycle.adopt(session)
    session.lifecycle.attach()

    assertFalse(state.styleAuthority.updateLoadedStyle(session, RecordingStyleBinding()))
    assertFalse(state.styleAuthority.markStyleReady(session))
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
    session.lifecycle = state.lifecycle.createRetainedEngineLifecycle(session, session)
    state.lifecycle.adopt(session)
    session.lifecycle.attach()
    val token = state.reservePresentation()
    state.publishPresentation(token, session)
    assertSame(session, state.currentMapAttachment?.adapter)
    assertTrue(state.styleAuthority.updateLoadedStyle(session, RecordingStyleBinding()))
    assertTrue(state.styleAuthority.markStyleReady(session))
    assertEquals(StyleLoadState.Ready, state.style.loadState)

    session.close()

    assertNull(state.currentMapAttachment)
    assertNull(state.retainedAdapter(session.presentationCompatibilityKey))
    assertEquals(StyleLoadState.Pending, state.style.loadState)
    finishCleanup.complete(Unit)
    assertFailsWith<MapCleanupException> { session.awaitClosed() }
    testScheduler.runCurrent()

    state.durableStyleCallbacks().onStyleFailed(session, "stale callback")
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
    session.lifecycle = state.lifecycle.createRetainedEngineLifecycle(session, session)
    state.lifecycle.adopt(session)
    session.lifecycle.attach()
    val token = state.reservePresentation()
    state.publishPresentation(token, session)
    assertTrue(state.styleAuthority.updateLoadedStyle(session, RecordingStyleBinding()))
    assertTrue(state.styleAuthority.markStyleReady(session))

    state.releasePresentation(token, session)
    testScheduler.runCurrent()
    assertNull(state.currentMapAttachment)
    assertSame(session, state.retainedAdapter(session.presentationCompatibilityKey))
    assertEquals(StyleLoadState.Ready, state.style.loadState)

    session.close()

    assertNull(state.retainedAdapter(session.presentationCompatibilityKey))
    assertEquals(StyleLoadState.Pending, state.style.loadState)
    finishCleanup.complete(Unit)
    session.awaitClosed()
    state.close()
    state.awaitClosed()
    runtime.close()
  }

  @Test
  fun a_presentation_that_returns_to_the_retained_session_keeps_its_style() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Demo)
    val session = BoundLifecycleSession()
    session.lifecycle = state.lifecycle.createRetainedEngineLifecycle(session, session)
    state.lifecycle.adopt(session)
    session.lifecycle.attach()
    val first = state.reservePresentation()
    state.publishPresentation(first, session)
    val style = RecordingStyleBinding()
    assertTrue(state.styleAuthority.updateLoadedStyle(session, style))
    state.releasePresentation(first, session)
    testScheduler.runCurrent()

    // A density change during attachment abandons a new session before it publishes.
    val abandoned = state.reservePresentation()
    assertTrue(state.lifecycle.selectAdapterForPresentation(PresentationTestAdapter()))
    assertNull(state.style.currentLoadedStyle())
    state.releasePresentation(abandoned, null)
    val returning = state.reservePresentation()
    assertTrue(state.lifecycle.selectAdapterForPresentation(session))
    state.publishPresentation(returning, session)

    assertTrue(style.isLoaded, "the retained session's style was invalidated")
    assertTrue(state.styleAuthority.updateLoadedStyle(session, style))
    assertSame(style, state.style.currentLoadedStyle())
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
    session.lifecycle = state.lifecycle.createRetainedEngineLifecycle(session, session)
    state.lifecycle.adopt(session)
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
    session.lifecycle = state.lifecycle.createRetainedEngineLifecycle(session, session)
    state.lifecycle.adopt(session)
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
      callbacks.onStyleFailed(map, "style refused")
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
    callbacks.onStyleReady(adapter)
    state.publishPresentation(token, adapter)

    assertEquals(StyleLoadState.Ready, state.style.loadState)
    assertSame(style, state.style.currentLoadedStyle())
    state.close()
    runtime.close()
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
  fun a_detached_map_keeps_durable_camera_commands_and_skips_surface_reads() = runTest {
    val fixture = presentationFixture()
    fixture.state.releasePresentation(fixture.token, fixture.adapter)
    val position = CameraPosition(zoom = 4.0)

    fixture.state.setCameraPosition(position)
    assertEquals(position, fixture.state.cameraPosition)
    assertEquals(CameraPosition(), fixture.adapter.lastCameraPosition)
    assertNull(fixture.state.positionFromScreenLocation(DpOffset.Zero))
    val viewportReads = fixture.adapter.viewportReads
    fixture.adapter.lastCameraPosition = CameraPosition(zoom = 7.0)
    assertNull(fixture.state.attachmentAuthority.synchronizeCamera(fixture.adapter))
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

    state.durableStyleCallbacks().onStyleFailed(adapter, "style refused")

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
    callbacks.onStyleReady(adapter)
    state.releasePresentation(token, adapter)
    testScheduler.advanceUntilIdle()

    style.removeSource("tiles")
    callbacks.onStyleSourcesChanged(adapter)

    assertTrue(state.style.sources.none())
    state.close()
    runtime.close()
  }

  @Test
  fun a_live_source_handle_is_ready_bound_and_cannot_target_a_replacement_style() = runTest {
    val fixture = presentationFixture()
    val firstStyle =
      RecordingStyleBinding(
        sources =
          listOf(
            GeoJsonSource(
              id = "points",
              data = GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[]}"""),
            )
          )
      )

    assertNull(fixture.state.style.sources["points"])
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, firstStyle)
    assertNull(fixture.state.style.sources["points"])
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)

    val handle = assertIs<GeoJsonSourceHandle>(fixture.state.style.sources["points"])
    assertNull(fixture.state.style.sources["missing"])
    handle.setFeatureState("7", buildJsonObject { put("selected", true) })
    assertEquals(
      true,
      firstStyle.featureState("points", null, "7")["selected"]?.jsonPrimitive?.boolean,
    )

    fixture.state.style.asMutable!!.baseStyle = BaseStyle.Json("replacement")
    handle.setFeatureState("7", buildJsonObject { put("stale", true) })
    val replacement =
      RecordingStyleBinding(
        sources =
          listOf(
            GeoJsonSource(
              id = "points",
              data = GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[]}"""),
            )
          )
      )
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, replacement)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)

    handle.setFeatureState("7", buildJsonObject { put("stale", true) })
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
      )
    val declaredRevision =
      StyleSnapshot(
        sources = listOf(geoJson.definition()),
        layers = emptyList(),
        images = emptyList(),
      )
    val loadedStyle = RecordingStyleBinding()
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, loadedStyle)
    fixture.applyRevision(loadedStyle, declaredRevision)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
    val handle = assertIs<GeoJsonSourceHandle>(fixture.state.style.sources["shared"])
    val vector = VectorTileSource("shared", "https://example.com/tiles.json")

    fixture.applyRevision(
      loadedStyle,
      StyleSnapshot(
        sources = listOf(vector.definition()),
        layers = emptyList(),
        images = emptyList(),
      ),
    )
    handle.setFeatureState("7", buildJsonObject { put("stale", true) })
    assertEquals(JsonObject(emptyMap()), loadedStyle.featureState("shared", null, "7"))

    assertNull(handle.getFeatureState("7"))
    assertEquals(JsonObject(emptyMap()), loadedStyle.featureState("shared", null, "7"))
    fixture.close()
  }

  @Test
  fun a_base_source_handle_does_not_revive_after_same_id_replacement() = runTest {
    val fixture = presentationFixture()
    val original = attributedVectorSource("shared", "original")
    val binding = RecordingStyleBinding(sources = listOf(original))
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
    val stale = assertIs<VectorTileSourceHandle>(fixture.state.style.sources["shared"])

    fixture.state.style.sources["shared"]!!.asMutable!!.remove()
    fixture.state.style.awaitCommands()
    val replacement =
      checkNotNull(fixture.state.style.sources.add(attributedVectorSource("shared", "replacement")))

    assertEquals("replacement", replacement.attributionHtml)
    assertEquals("original", stale.attributionHtml)
    // The lookup returned the same handle that removed the source.
    assertFailsWith<IllegalStateException> { stale.resetFeatureStates("layer") }
    fixture.close()
  }

  @Test
  fun a_declarative_source_handle_does_not_revive_after_same_type_replacement() = runTest {
    val fixture = presentationFixture()
    val original = attributedVectorSource("shared", "original")
    val declaredRevision = StyleSnapshot(listOf(original.definition()), emptyList(), emptyList())
    val binding = RecordingStyleBinding()
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.applyRevision(binding, declaredRevision)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
    val stale = assertIs<VectorTileSourceHandle>(fixture.state.style.sources["shared"])
    val replacement = attributedVectorSource("shared", "replacement")

    fixture.applyRevision(
      binding,
      StyleSnapshot(listOf(replacement.definition()), emptyList(), emptyList()),
    )

    stale.resetFeatureStates("layer")
    assertEquals("replacement", fixture.state.style.sources["shared"]?.attributionHtml)
    fixture.close()
  }

  @Test
  fun geojson_cluster_queries_return_null_for_a_non_cluster_feature() = runTest {
    val fixture = presentationFixture()
    val loadedStyle =
      RecordingStyleBinding(
        sources =
          listOf(
            GeoJsonSource(
              id = "points",
              data = GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[]}"""),
            )
          )
      )
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, loadedStyle)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
    val handle = assertIs<GeoJsonSourceHandle>(fixture.state.style.sources["points"])
    val point =
      buildFeatureCollection<Geometry, JsonObject?> {
          addFeature(geometry = Point(Position(0.0, 0.0)))
        }
        .features
        .single()

    assertFalse(handle.isCluster(point))
    assertNull(handle.getClusterExpansionZoom(point))
    assertNull(handle.getClusterChildren(point))
    assertNull(handle.getClusterLeaves(point, limit = 1, offset = 0))
    fixture.close()
  }

  @Test
  fun geojson_cluster_leaves_validate_limit_and_offset() = runTest {
    val fixture = presentationFixture()
    val loadedStyle =
      RecordingStyleBinding(
        sources =
          listOf(
            GeoJsonSource(
              id = "points",
              data = GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[]}"""),
            ) {
              cluster = true
            }
          )
      )
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, loadedStyle)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
    val handle = assertIs<GeoJsonSourceHandle>(fixture.state.style.sources["points"])
    val cluster = Feature(Point(Position(0.0, 0.0)), buildJsonObject { put("cluster_id", 1) })

    val negativeLimit =
      assertFailsWith<IllegalArgumentException> { handle.getClusterLeaves(cluster, -1, 0) }
    assertEquals("limit must not be negative, was -1", negativeLimit.message)
    val negativeOffset =
      assertFailsWith<IllegalArgumentException> { handle.getClusterLeaves(cluster, 1, -2) }
    assertEquals("offset must not be negative, was -2", negativeOffset.message)
    // The recording engine answers null, so an empty result shows that it was not asked.
    assertEquals(emptyList(), assertNotNull(handle.getClusterLeaves(cluster, 0, 0)).features)
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
            )
          )
      )
    state.durableStyleCallbacks().onStyleChanged(first, firstStyle)
    state.durableStyleCallbacks().onStyleReady(first)
    val handle = assertIs<GeoJsonSourceHandle>(state.style.sources["points"])
    state.releasePresentation(firstToken, first)
    testScheduler.advanceUntilIdle()

    val secondToken = state.reservePresentation()
    state.publishPresentation(secondToken, RetainedAdapter(failOnClose = false))

    handle.setFeatureState("7", buildJsonObject { put("stale", true) })
    assertEquals(JsonObject(emptyMap()), firstStyle.featureState("points", null, "7"))
    state.close()
    runtime.close()
  }

  @Test
  fun a_live_layer_handle_reads_and_writes_only_its_loaded_style() = runTest {
    val fixture = presentationFixture()
    val loadedStyle = RecordingStyleBinding(layers = listOf(TestLayer("background", "background")))
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, loadedStyle)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)

    val handle = assertIs<LayerHandle>(fixture.state.style.layers["background"])
    assertNull(fixture.state.style.layers["missing"])
    val mutable = assertNotNull(handle.asMutable)
    mutable.setPaintProperty("background-opacity", JsonPrimitive(0.5))
    assertEquals(JsonPrimitive(0.5), handle.getProperty("background-opacity"))

    // An expired handle reads null and ignores writes.
    loadedStyle.invalidate()
    assertNull(handle.getProperty("background-opacity"))
    assertNull(handle.asMutable)
    mutable.setPaintProperty("background-opacity", JsonPrimitive(0.25))
    assertEquals(JsonPrimitive(0.5), loadedStyle.layerProperty("background", "background-opacity"))
    fixture.close()
  }

  @Test
  fun a_layer_handle_does_not_revive_after_structural_replacement() = runTest {
    val fixture = presentationFixture()
    val layer = TestLayer("background", "background")
    val original = StyleSnapshot.Layer(layer.definition(), Anchor.Top, null, null)
    val declaredRevision = StyleSnapshot(emptyList(), listOf(original), emptyList())
    val binding = RecordingStyleBinding()
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.applyRevision(binding, declaredRevision)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
    val stale = checkNotNull(fixture.state.style.layers["background"])

    fixture.applyRevision(
      binding,
      StyleSnapshot(emptyList(), listOf(original.copy(anchor = Anchor.Bottom)), emptyList()),
    )

    assertNull(stale.getProperty("background-opacity"))
    assertTrue(fixture.state.style.layers["background"] != null)
    fixture.close()
  }

  @Test
  fun a_ready_callback_waiting_for_a_command_cannot_ready_a_replacement_style() = runTest {
    val fixture = presentationFixture()
    val binding = QueuedOwnerStyleBinding(RecordingStyleBinding())
    val callbacks = fixture.state.durableStyleCallbacks()
    callbacks.onStyleChanged(fixture.adapter, binding)
    callbacks.onStyleReady(fixture.adapter)
    binding.ownerBusy = true
    val command =
      async(start = CoroutineStart.UNDISPATCHED) {
        runCatching { fixture.state.style.sources.add(attributedVectorSource("queued", "old")) }
      }
    callbacks.onStyleReady(fixture.adapter)
    val replacement = RecordingStyleBinding()
    callbacks.onStyleChanged(fixture.adapter, replacement)
    binding.runOwnerTasks()
    command.await()
    fixture.state.style.awaitCommands()
    assertEquals(StyleLoadState.Loading, fixture.state.style.loadState)
    assertTrue(fixture.state.style.sources.none())
    callbacks.onStyleReady(fixture.adapter)
    assertEquals(StyleLoadState.Ready, fixture.state.style.loadState)
    fixture.close()
  }

  @Test
  fun publishing_a_replacement_style_makes_handles_unavailable_until_it_is_ready() = runTest {
    val fixture = presentationFixture()
    val first = RecordingStyleBinding()
    assertTrue(fixture.state.styleAuthority.updateLoadedStyle(fixture.adapter, first))
    assertTrue(fixture.state.styleAuthority.markStyleReady(fixture.adapter))
    assertEquals(StyleLoadState.Ready, fixture.state.style.loadState)

    val replacement = RecordingStyleBinding()
    assertTrue(fixture.state.styleAuthority.updateLoadedStyle(fixture.adapter, replacement))

    assertEquals(StyleLoadState.Loading, fixture.state.style.loadState)
    assertNull(fixture.state.style.layers["anything"])
    assertTrue(fixture.state.styleAuthority.markStyleReady(fixture.adapter))
    assertEquals(StyleLoadState.Ready, fixture.state.style.loadState)
    fixture.close()
  }

  @Test
  fun loaded_style_resource_objects_preserve_engine_order_and_empty_on_invalidation() = runTest {
    val fixture = presentationFixture()
    val sources =
      listOf(
        attributedVectorSource("bottom", "first"),
        attributedVectorSource("top", "second"),
      )
    val layers = listOf(TestLayer("bottom", "background"), TestLayer("top", "background"))
    val binding = RecordingStyleBinding(sources = sources, layers = layers)
    val styleSources = fixture.state.style.sources
    val styleLayers = fixture.state.style.layers
    val styleImages = fixture.state.style.images

    assertTrue(styleSources.none())
    assertTrue(styleLayers.none())
    // Commands without a ready style do nothing.
    assertNull(styleSources.add(attributedVectorSource("unready", "unready")))
    styleImages.set("unready", ResolvedStyleImage(PreparedImage.fromBitmap(FakeImageBitmap(1, 1))))
    styleImages.remove("unready")
    fixture.state.style.globalState.setProperty("value", JsonPrimitive(1))
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
    assertTrue(binding.imageIds.isEmpty())
    assertNull(fixture.state.style.globalState.get()?.get("value"))

    assertSame(styleSources, fixture.state.style.sources)
    assertSame(styleLayers, fixture.state.style.layers)
    assertSame(styleImages, fixture.state.style.images)
    assertEquals(listOf("bottom", "top"), styleSources.map { it.id })
    assertEquals(listOf("bottom", "top"), styleLayers.map { it.id })

    fixture.state.style.asMutable!!.baseStyle = BaseStyle.Json("replacement")
    assertTrue(styleSources.none())
    assertTrue(styleLayers.none())
    fixture.close()
  }

  @Test
  fun a_style_that_becomes_unready_while_an_add_waits_returns_null() = runTest {
    val fixture = presentationFixture()
    val binding = RecordingStyleBinding()
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
    val style = fixture.state.style
    val release = CompletableDeferred<Unit>()
    val commit =
      launch(start = CoroutineStart.UNDISPATCHED) {
        style.owner.resourceCommands.withCommit { release.await() }
      }
    val addition =
      async(start = CoroutineStart.UNDISPATCHED) {
        runCatching { style.sources.add(attributedVectorSource("queued", "queued")) }
      }
    assertFalse(addition.isCompleted)
    style.loadState = StyleLoadState.Loading
    assertNull(style.sources.add(attributedVectorSource("immediate", "immediate")))
    release.complete(Unit)
    commit.join()
    assertNull(addition.await().getOrThrow())
    assertTrue(binding.sources.isEmpty())
    fixture.close()
  }

  @Test
  fun runtime_shutdown_cancels_an_add_waiting_for_the_command_queue() = runTest {
    val fixture = presentationFixture()
    val binding = RecordingStyleBinding()
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
    val style = fixture.state.style
    val release = CompletableDeferred<Unit>()
    val commit =
      launch(start = CoroutineStart.UNDISPATCHED) {
        style.owner.resourceCommands.withCommit { release.await() }
      }
    val addition =
      async(start = CoroutineStart.UNDISPATCHED) {
        runCatching { style.sources.add(attributedVectorSource("queued", "queued")) }
      }
    assertFalse(addition.isCompleted)
    fixture.runtime.close()
    fixture.runtime.awaitClosed()
    release.complete(Unit)
    commit.join()
    assertIs<CancellationException>(addition.await().exceptionOrNull())
    assertTrue(binding.sources.isEmpty())
    fixture.close()
  }

  @Test
  fun a_source_add_distinguishes_engine_rejections_argument_errors_and_cancellation() = runTest {
    for (failure in
      listOf(
        IllegalArgumentException("invalid definition"),
        CancellationException("cancelled"),
        StyleMutationException("engine refusal", null),
      )) {
      val fixture = presentationFixture()
      val recorded = RecordingStyleBinding()
      val binding =
        object : StyleBinding by recorded {
          override fun addSource(definition: SourceDefinition): Boolean = throw failure
        }
      fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
      fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
      val result = runCatching {
        fixture.state.style.sources.add(attributedVectorSource("added", "added"))
      }
      val thrown = assertNotNull(result.exceptionOrNull())
      if (failure is StyleMutationException) {
        assertIs<StyleHandleException>(thrown)
        // Coroutine stack recovery can insert a copy of the public exception into the chain.
        val engineFailure = generateSequence(thrown.cause) { it.cause }.last()
        assertIs<StyleMutationException>(engineFailure)
        assertEquals(failure.message, engineFailure.message)
      } else {
        assertEquals(failure::class, thrown::class)
        assertEquals(failure.message, thrown.message)
      }
      assertTrue(recorded.sources.isEmpty())
      assertTrue(fixture.state.style.owner.resourceCommands.sourceIds().isEmpty())
      fixture.close()
    }
  }

  @Test
  fun imperative_source_commands_publish_results_and_normalize_engine_refusal() = runTest {
    val fixture = presentationFixture()
    val binding = RecordingStyleBinding(refusedSourceRemovals = setOf("blocked"))
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
    val added = attributedVectorSource("added", "added attribution")
    val blocked = attributedVectorSource("blocked", "blocked attribution")

    val firstHandle = checkNotNull(fixture.state.style.sources.add(added))
    assertEquals("added", firstHandle.id)
    assertEquals("added", fixture.state.style.sources["added"]?.id)
    fixture.state.style.sources.add(blocked)
    val duplicate =
      assertFailsWith<IllegalStateException> { fixture.state.style.sources.add(added) }
    assertEquals("Source ID 'added' already exists", duplicate.message)
    assertEquals("added", firstHandle.id)
    assertNull(fixture.state.style.sources["missing"])
    firstHandle.remove()
    fixture.state.style.awaitCommands()
    assertNull(fixture.state.style.sources["added"])
    val replacementHandle = checkNotNull(fixture.state.style.sources.add(added))
    assertFailsWith<IllegalStateException> { firstHandle.remove() }
    assertTrue(binding.sourceExists("added") == true)
    assertFailsWith<IllegalStateException> {
      assertIs<VectorTileSourceHandle>(firstHandle).resetFeatureStates("layer")
    }
    assertEquals("added attribution", replacementHandle.attributionHtml)
    fixture.state.style.sources["added"]!!.asMutable!!.remove()
    fixture.state.style.awaitCommands()

    fixture.state.style.sources["blocked"]!!.asMutable!!.remove()
    fixture.state.style.awaitCommands()
    assertTrue("blocked" in binding.sources)
    assertTrue(fixture.state.style.sources["blocked"] != null)
    fixture.close()
  }

  @Test
  fun a_source_add_is_one_owner_task_and_returns_once_it_has_run() = runTest {
    val fixture = presentationFixture()
    val recorded = RecordingStyleBinding()
    val binding = QueuedOwnerStyleBinding(recorded)
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
    val style = fixture.state.style
    binding.ownerBusy = true

    val added =
      async(start = CoroutineStart.UNDISPATCHED) {
        style.sources.add(attributedVectorSource("added", "attribution"))
      }
    assertFalse(added.isCompleted, "the add returns its handle once the command has run")
    assertFalse("added" in recorded.sources, "the engine is reached only from the owner task")
    assertEquals(1, binding.ownerTasks, "existence check, insertion, and refresh share one task")
    binding.runOwnerTasks()
    assertEquals("attribution", added.await()?.attributionHtml)
    assertEquals(0, binding.ownerTasks)

    checkNotNull(added.await()).remove()
    assertEquals(1, binding.ownerTasks, "removal and refresh share one task")
    binding.runOwnerTasks()
    style.awaitCommands()
    assertNull(style.sources["added"])
    assertFalse("added" in recorded.sources)
    fixture.close()
  }

  @Test
  fun image_source_handle_writes_pass_prepared_images_through_in_call_order() = runTest {
    val fixture = presentationFixture()
    val binding = RecordingStyleBinding()
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
    val first = PreparedImage.fromBitmap(FakeImageBitmap(1, 1))
    val second = PreparedImage.fromBitmap(FakeImageBitmap(2, 1))
    val moved = ImageQuad.copy(topLeft = Position(-2.0, 1.0))
    val source = ImageSource("image", ImageQuad, first)

    val handle = checkNotNull(fixture.state.style.sources.add(source))
    assertSame(first, binding.addedImageSourceImages["image"])
    handle.setImage(first)
    handle.setUri("https://example.invalid/image.png")
    handle.setBounds(moved)
    handle.setImage(second)

    assertEquals(
      listOf(
        first,
        "https://example.invalid/image.png",
        listOf(moved.topLeft, moved.topRight, moved.bottomRight, moved.bottomLeft),
        second,
      ),
      binding.imageSourceWrites.map { it.second },
    )
    assertSame(
      first,
      binding.imageSourceWrites.first().second,
      "a write neither copies nor converts",
    )

    // A handle to a source replaced under the same ID writes nothing.
    handle.remove()
    fixture.state.style.sources.add(source)
    binding.imageSourceWrites.clear()
    assertFailsWith<IllegalStateException> { handle.setImage(second) }
    assertTrue(binding.imageSourceWrites.isEmpty())
    fixture.close()
  }

  @Test
  fun an_image_batch_that_sets_and_removes_is_one_owner_task() = runTest {
    val fixture = presentationFixture()
    val recorded = RecordingStyleBinding()
    val binding = QueuedOwnerStyleBinding(recorded)
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
    val style = fixture.state.style
    val image = ResolvedStyleImage(PreparedImage.fromBitmap(FakeImageBitmap(1, 1)))

    // The removal supersedes "b" in the pending batch, which then both sets and removes.
    val release = CompletableDeferred<Unit>()
    val commit =
      launch(start = CoroutineStart.UNDISPATCHED) {
        style.owner.resourceCommands.withCommit { release.await() }
      }
    style.images.setAll(mapOf("a" to image, "b" to image))
    style.images.remove("b")
    binding.ownerBusy = true
    release.complete(Unit)
    commit.join()

    assertEquals(1, binding.ownerTasks)
    binding.runOwnerTasks()
    assertEquals(0, binding.ownerTasks, "the removal shares the task that set the image")
    binding.ownerBusy = false
    style.awaitCommands()
    assertEquals(listOf("set a 1", "remove b"), recorded.imageWrites)
    fixture.close()
  }

  @Test
  fun a_guarded_operation_that_returns_null_runs_once() = runTest {
    val fixture = presentationFixture()
    val binding = RecordingStyleBinding()
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
    var runs = 0

    val result: Unit? =
      fixture.state.style.operationGuard(binding).run {
        runs++
        null
      }

    assertNull(result)
    assertEquals(1, runs)
    fixture.close()
  }

  @Test
  fun a_source_add_in_flight_across_a_style_change_returns_null_and_does_not_claim_the_id() =
    runTest {
      val fixture = presentationFixture()
      val binding = QueuedOwnerStyleBinding(RecordingStyleBinding())
      fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
      fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
      val style = fixture.state.style
      binding.ownerBusy = true
      val added =
        async(start = CoroutineStart.UNDISPATCHED) {
          runCatching { style.sources.add(attributedVectorSource("shared", "imperative")) }
        }
      assertEquals(1, binding.ownerTasks)

      // The base style changes before the owner runs the addition.
      val replacement = RecordingStyleBinding()
      fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, replacement)
      fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
      binding.runOwnerTasks()
      assertNull(added.await().getOrThrow())

      // The next style may declare the same ID, and no stale record answers for it.
      val declared =
        StyleSnapshot(
          listOf(attributedVectorSource("shared", "declared").definition()),
          emptyList(),
          emptyList(),
        )
      fixture.applyRevision(replacement, declared)
      assertNull(style.owner.resourceCommands.sourceDefinition("shared"))
      assertTrue(style.owner.resourceCommands.sourceIds().isEmpty())
      assertTrue("shared" in replacement.sources)
      assertEquals(declared, fixture.state.style.declaredRevision)
      fixture.close()
    }

  @Test
  fun removing_a_source_whose_existence_is_unknown_still_asks_the_engine() = runTest {
    val fixture = presentationFixture()
    val recorded = RecordingStyleBinding()
    var existenceKnown = true
    val binding =
      object : StyleBinding by recorded {
        override fun sourceExists(sourceId: String): Boolean? =
          if (existenceKnown) recorded.sourceExists(sourceId) else null
      }
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)

    val handle =
      checkNotNull(fixture.state.style.sources.add(attributedVectorSource("added", "attribution")))
    existenceKnown = false
    handle.remove()
    fixture.state.style.awaitCommands()
    assertFalse("added" in recorded.sources)
    assertNull(fixture.state.style.sources["added"])
    fixture.close()
  }

  @Test
  fun adding_an_image_after_engine_eviction_retires_the_base_image_handle() = runTest {
    val fixture = presentationFixture()
    val image = FakeImageBitmap(1, 1)
    val binding = RecordingStyleBinding(images = listOf("marker" to image))
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
    val old = assertNotNull(fixture.state.style.images["marker"]?.asMutable)

    binding.removeImage("marker")
    val replacement = fixture.state.style.setImage("marker", image)

    old.remove()
    fixture.state.style.awaitCommands()
    assertTrue(binding.imageExists("marker"))
    replacement.remove()
    fixture.state.style.awaitCommands()
    fixture.close()
  }

  @Test
  fun setting_over_a_base_style_image_replaces_it_and_expires_its_handle() = runTest {
    val fixture = presentationFixture()
    val image = FakeImageBitmap(1, 1)
    val binding = RecordingStyleBinding(images = listOf("marker" to image))
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
    val existing = assertNotNull(fixture.state.style.images["marker"]?.asMutable)

    val replacement = fixture.state.style.setImage("marker", image)

    assertEquals(listOf("marker"), binding.replacedImages)
    existing.remove()
    replacement.remove()
    fixture.state.style.awaitCommands()
    assertFalse(binding.imageExists("marker"))
    fixture.close()
  }

  @Test
  fun image_lookup_requires_a_ready_style_after_waiting_and_reading() = runTest {
    val fixture = presentationFixture()
    val style = fixture.state.style
    val recorded = RecordingStyleBinding(images = listOf("marker" to FakeImageBitmap(1, 1)))
    var reads = 0
    var becomesLoadingDuringRead = false
    val binding =
      object : StyleBinding by recorded {
        override fun imageExists(id: String): Boolean {
          reads++
          if (becomesLoadingDuringRead) style.loadState = StyleLoadState.Loading
          return recorded.imageExists(id)
        }
      }
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
    val release = CompletableDeferred<Unit>()
    try {
      val commit =
        launch(start = CoroutineStart.UNDISPATCHED) {
          style.owner.resourceCommands.withCommit { release.await() }
        }
      val lookup = async(start = CoroutineStart.UNDISPATCHED) { style.images["marker"] }
      assertFalse(lookup.isCompleted)
      style.loadState = StyleLoadState.Loading
      release.complete(Unit)
      commit.join()
      assertNull(lookup.await())
      assertEquals(0, reads)

      fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
      becomesLoadingDuringRead = true
      assertNull(style.images["marker"])
      assertEquals(1, reads)
    } finally {
      release.complete(Unit)
      fixture.close()
    }
  }

  @Test
  fun a_removal_that_a_later_write_supersedes_expires_the_handle_without_misuse() = runTest {
    val fixture = presentationFixture()
    val binding = RecordingStyleBinding()
    val image = FakeImageBitmap(1, 1)
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
    val style = fixture.state.style
    val handle = style.setImage("marker", image)
    val release = CompletableDeferred<Unit>()
    val commit =
      launch(start = CoroutineStart.UNDISPATCHED) {
        style.owner.resourceCommands.withCommit { release.await() }
      }

    // The replacement queued behind the removal runs in its place.
    handle.remove()
    style.images.set("marker", ResolvedStyleImage(PreparedImage.fromBitmap(image)))
    release.complete(Unit)
    commit.join()
    style.awaitCommands()

    assertTrue(binding.imageExists("marker"))
    assertNull(handle.asMutable)
    handle.remove()
    fixture.close()
  }

  @Test
  fun imperative_image_commands_set_and_remove() = runTest {
    val fixture = presentationFixture()
    val binding = RecordingStyleBinding()
    val image = FakeImageBitmap(1, 1)
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)

    val handle = fixture.state.style.setImage("marker", image)
    assertEquals(setOf("marker"), binding.imageIds)
    assertNull(fixture.state.style.images["missing"])
    handle.remove()
    fixture.state.style.awaitCommands()
    assertTrue(binding.imageIds.isEmpty())
    val replacement = fixture.state.style.setImage("marker", image)
    assertFailsWith<IllegalStateException> { handle.remove() }
    assertEquals(setOf("marker"), binding.imageIds)
    fixture.state.style.asMutable!!.baseStyle = BaseStyle.Empty
    replacement.remove()
    assertEquals(setOf("marker"), binding.imageIds)
    fixture.close()
  }

  @Test
  fun setting_an_image_replaces_it_in_place_and_expires_the_previous_handle() = runTest {
    val fixture = presentationFixture()
    val binding = RecordingStyleBinding()
    val image = FakeImageBitmap(1, 1)
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)

    val added = fixture.state.style.setImage("marker", image)
    assertEquals(setOf("marker"), binding.imageIds)
    assertTrue(binding.replacedImages.isEmpty())
    val replaced = fixture.state.style.setImage("marker", image)
    assertEquals(listOf("marker"), binding.replacedImages)
    assertEquals(setOf("marker"), binding.imageIds)
    added.remove()
    replaced.remove()
    fixture.state.style.awaitCommands()
    assertTrue(binding.imageIds.isEmpty())
    fixture.close()
  }

  @Test
  fun a_newer_image_command_supersedes_a_pending_one_for_the_same_id() = runTest {
    val fixture = presentationFixture()
    val binding = RecordingStyleBinding()
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
    val style = fixture.state.style
    fun prepared(width: Int) =
      ResolvedStyleImage(PreparedImage.fromBitmap(FakeImageBitmap(width, 1)))
    // Holds the queue so that every command in [block] is still pending when the next is submitted.
    suspend fun pending(block: () -> Unit) {
      val release = CompletableDeferred<Unit>()
      val commit =
        launch(start = CoroutineStart.UNDISPATCHED) {
          style.owner.resourceCommands.withCommit { release.await() }
        }
      block()
      release.complete(Unit)
      commit.join()
      style.awaitCommands()
    }

    pending {
      style.images.setAll(mapOf("a" to prepared(1), "b" to prepared(1)))
      style.images.set("a", prepared(2))
      style.images.set("a", prepared(3))
      style.images.remove("b")
    }
    assertEquals(listOf("set a 3", "remove b"), binding.imageWrites)

    // A handle's removal yields to a later set, which replaces the image in place.
    val handle = assertNotNull(style.images["a"]?.asMutable)
    binding.imageWrites.clear()
    pending {
      handle.remove()
      style.images.set("a", prepared(4))
    }
    assertEquals(listOf("set a 4"), binding.imageWrites)

    // A handle's removal does not supersede a pending replacement, which expires the handle.
    val expiring = assertNotNull(style.images["a"]?.asMutable)
    binding.imageWrites.clear()
    pending {
      style.images.set("a", prepared(5))
      expiring.remove()
    }
    assertEquals(listOf("set a 5"), binding.imageWrites)
    assertEquals(setOf("a"), binding.imageIds)

    // The first pending command applies the newest write, so a state change queued between the two
    // commands cannot strand it.
    binding.imageWrites.clear()
    pending {
      style.images.set("a", prepared(6))
      launch(start = CoroutineStart.UNDISPATCHED) {
        style.owner.resourceCommands.withCommit {
          style.loadState = StyleLoadState.Loading
        }
      }
      style.images.set("a", prepared(7))
    }
    assertEquals(listOf("set a 7"), binding.imageWrites)

    // A batch keeps its IDs when the caller changes the map after submission.
    val commands = style.owner.resourceCommands
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
    binding.imageWrites.clear()
    pending {
      val batch = mutableMapOf("c" to prepared(8))
      style.images.setAll(batch)
      batch.clear()
    }
    assertEquals(listOf("set c 8"), binding.imageWrites)

    // A dropped command releases its pending write, including the pixels it would install.
    binding.imageWrites.clear()
    pending {
      launch(start = CoroutineStart.UNDISPATCHED) {
        commands.withCommit { style.loadState = StyleLoadState.Loading }
      }
      style.images.set("d", prepared(9))
    }
    assertEquals(emptyList(), binding.imageWrites)
    assertEquals(emptySet(), commands.pendingImageWriteIds())
    fixture.close()
  }

  @Test
  fun a_batch_failure_keeps_the_previous_image_handle_and_installs_other_images() = runTest {
    val fixture = presentationFixture()
    val binding = RecordingStyleBinding(refusedImageReplacements = setOf("marker"))
    val image = FakeImageBitmap(1, 1)
    fixture.state.missingImageResolver = { ResolvedStyleImage(PreparedImage.fromBitmap(image)) }
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)

    val added = fixture.state.style.setImage("marker", image)
    val prepared = ResolvedStyleImage(PreparedImage.fromBitmap(image))
    fixture.state.style.images.setAll(
      mapOf("before" to prepared, "marker" to prepared, "after" to prepared)
    )
    fixture.state.style.awaitCommands()
    assertEquals(setOf("before", "marker", "after"), binding.imageIds)
    // The image stays imperatively owned: the resolver does not treat it as missing.
    assertNull(fixture.state.styleAuthority.resolveMissingImage(fixture.adapter, "marker"))
    added.remove()
    fixture.state.style.awaitCommands()
    assertEquals(setOf("before", "after"), binding.imageIds)
    fixture.close()
  }

  /**
   * A set transition reaches the engine under the animator duration scale, the getter reports what
   * the engine holds, and a transition the style JSON holds is left alone.
   */
  @Test
  fun a_set_transition_is_scaled_for_the_engine() = runTest {
    val fixture = presentationFixture()
    val binding =
      RecordingStyleBinding(
        layers = listOf(TestLayer("background", "background")),
        animatorDurationScaleState = mutableStateOf(0.5f),
      )
    val transition = fixture.state.style.transition
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)

    assertEquals(TransitionOptions(), transition.get())

    val options = TransitionOptions(duration = 1.seconds, delay = 20.milliseconds)
    val scaled = TransitionOptions(duration = 500.milliseconds, delay = 10.milliseconds)
    transition.set(options)
    assertEquals(scaled, binding.transition)
    assertEquals(scaled, transition.get())

    fixture.state.style.light.set(Light(colorTransition = options))
    // Compared as numbers because Kotlin/JS prints the double 500.0 as 500.
    assertEquals(
      mapOf("duration" to 500.0, "delay" to 10.0),
      assertNotNull(fixture.state.style.light.getProperty("color-transition"))
        .jsonObject
        .mapValues { (_, value) -> value.jsonPrimitive.double },
    )

    val handle = assertNotNull(fixture.state.style.layers["background"])
    handle.asMutable!!.setPaintTransition("background-color", options)
    assertEquals(scaled, handle.getPaintTransition("background-color"))
    fixture.close()
  }

  @Test
  fun global_reads_drop_stale_results_and_preserve_cancellation() = runTest {
    val fixture = presentationFixture()
    val binding = QueuedOwnerStyleBinding(RecordingStyleBinding())
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
    binding.ownerBusy = true
    val cancelled =
      async(start = CoroutineStart.UNDISPATCHED) { fixture.state.style.globalState.get() }
    assertFalse(cancelled.isCompleted)
    cancelled.cancel()
    assertFailsWith<CancellationException> { cancelled.await() }
    val stale = async(start = CoroutineStart.UNDISPATCHED) { fixture.state.style.globalState.get() }
    assertFalse(stale.isCompleted)
    fixture.state.style.asMutable!!.baseStyle = BaseStyle.Json("replacement")
    binding.runOwnerTasks()
    assertNull(stale.await())
    fixture.close()
  }

  @Test
  fun global_reads_preserve_argument_errors() = runTest {
    val fixture = presentationFixture()
    val recorded = RecordingStyleBinding()
    val binding =
      object : StyleBinding by recorded {
        override fun lightProperty(name: String): JsonElement? =
          throw IllegalArgumentException(name)
      }
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
    assertEquals(
      "invalid",
      assertFailsWith<IllegalArgumentException> { fixture.state.style.light.getProperty("invalid") }
        .message,
    )
    fixture.close()
  }

  @Test
  fun transition_light_sky_and_projection_commands_target_only_a_ready_loaded_style() = runTest {
    val fixture = presentationFixture()
    val binding = RecordingStyleBinding()
    val transition = fixture.state.style.transition
    val light = fixture.state.style.light
    val sky = fixture.state.style.sky
    val projection = fixture.state.style.projection
    val options = TransitionOptions(duration = 1.seconds, delay = 20.milliseconds)

    assertNull(transition.get())
    assertNull(transition.placementTransitions())
    assertNull(light.getProperty("color"))
    assertNull(sky.getProperty("sky-color"))
    assertNull(projection.getProperty("type"))
    // Without a ready style, writes do nothing.
    transition.set(options)
    transition.setPlacementTransitions(false)
    light.set(Light())
    sky.set(Sky())
    projection.set(Projection())
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
    assertNotEquals(options, transition.get())

    transition.set(options)
    assertEquals(options, transition.get())

    fixture.state.style.asMutable!!.baseStyle = BaseStyle.Json("replacement")
    assertNull(transition.get())
    assertNull(light.getProperty("color"))
    assertNull(sky.getProperty("sky-color"))
    assertNull(projection.getProperty("type"))
    transition.set(options)
    assertNull(transition.get())
    fixture.close()
  }

  @Test
  fun a_resolved_missing_image_reaches_a_style_that_has_not_gone_ready() = runTest {
    val fixture = presentationFixture()
    val binding = RecordingStyleBinding()
    var calls = 0
    fixture.state.missingImageResolver = {
      calls++
      ResolvedStyleImage(PreparedImage.fromBitmap(FakeImageBitmap(1, 1)))
    }
    // The style is loading, not ready: the browser asks while it parses the tiles that decide
    // whether the style has loaded.
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    assertEquals(StyleLoadState.Loading, fixture.state.style.loadState)

    assertNotNull(fixture.state.styleAuthority.resolveMissingImage(fixture.adapter, "icon")).await()
    assertNotNull(fixture.state.styleAuthority.resolveMissingImage(fixture.adapter, "icon")).await()

    assertTrue(binding.imageExists("icon") == true)
    assertEquals(1, calls, "the map asked the resolver twice for one image ID")
    fixture.close()
  }

  @Test
  fun an_evicted_resolver_image_is_supplied_again_in_the_same_style() = runTest {
    val fixture = presentationFixture()
    val binding = RecordingStyleBinding()
    var calls = 0
    fixture.state.missingImageResolver = {
      calls++
      ResolvedStyleImage(PreparedImage.fromBitmap(FakeImageBitmap(1, 1)))
    }
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)

    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
    var previous: MutableStyleImageHandle? = null

    repeat(3) { eviction ->
      assertNotNull(fixture.state.styleAuthority.resolveMissingImage(fixture.adapter, "icon"))
        .await()
      assertTrue(binding.imageExists("icon"), "image stayed absent after eviction $eviction")
      assertEquals(eviction + 1, calls)
      previous?.remove()
      fixture.state.style.awaitCommands()
      assertTrue(binding.imageExists("icon"))
      previous = assertNotNull(fixture.state.style.images["icon"]?.asMutable)
      // Native eviction removes the engine image without going through the image handle.
      binding.removeImage("icon")
    }
    fixture.close()
  }

  @Test
  fun a_resolver_cannot_restore_an_explicitly_owned_image() = runTest {
    val fixture = presentationFixture()
    val binding = RecordingStyleBinding()
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var calls = 0
    fixture.state.missingImageResolver = {
      calls++
      started.complete(Unit)
      release.await()
      ResolvedStyleImage(PreparedImage.fromBitmap(FakeImageBitmap(1, 1)))
    }
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
    val pending =
      assertNotNull(fixture.state.styleAuthority.resolveMissingImage(fixture.adapter, "icon"))
    started.await()
    fixture.state.style.setImage("icon", FakeImageBitmap(1, 1))
    // An explicit addition can answer a pending Native request and become eligible for eviction.
    binding.removeImage("icon")
    release.complete(Unit)
    pending.await()
    repeat(3) {
      assertNull(fixture.state.styleAuthority.resolveMissingImage(fixture.adapter, "icon"))
    }
    assertEquals(1, calls)
    assertFalse(binding.imageExists("icon"))
    fixture.close()
  }

  @Test
  fun declarative_image_ownership_suppresses_resolution_until_released() = runTest {
    val fixture = presentationFixture()
    var rejectImage = true
    val binding =
      RecordingStyleBinding(
        beforeAddImage = { if (rejectImage) throw StyleMutationException("refused", null) }
      )
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val image = FakeImageBitmap(1, 1)
    var calls = 0
    fixture.state.missingImageResolver = {
      calls++
      started.complete(Unit)
      release.await()
      ResolvedStyleImage(PreparedImage.fromBitmap(image))
    }
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    val pending =
      assertNotNull(fixture.state.styleAuthority.resolveMissingImage(fixture.adapter, "icon"))
    started.await()
    // The revision owns the ID before its image reaches the engine, including when replay fails.
    fixture.applyRevision(
      binding,
      StyleSnapshot(
        sources = emptyList(),
        layers = emptyList(),
        images = listOf(StyleImageDefinition("icon", PreparedImage.fromBitmap(image), false, null)),
      ),
    )
    release.complete(Unit)
    pending.await()
    repeat(3) {
      assertNull(fixture.state.styleAuthority.resolveMissingImage(fixture.adapter, "icon"))
    }
    assertEquals(1, calls)
    assertFalse(binding.imageExists("icon"))
    rejectImage = false
    fixture.applyRevision(binding, StyleSnapshot.Empty)
    assertNotNull(fixture.state.styleAuthority.resolveMissingImage(fixture.adapter, "icon")).await()
    assertEquals(2, calls)
    assertTrue(binding.imageExists("icon"))
    fixture.close()
  }

  @Test
  fun concurrent_missing_image_requests_share_the_resolution() = runTest {
    val fixture = presentationFixture()
    val binding = RecordingStyleBinding()
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var calls = 0
    fixture.state.missingImageResolver = {
      calls++
      started.complete(Unit)
      release.await()
      ResolvedStyleImage(PreparedImage.fromBitmap(FakeImageBitmap(1, 1)))
    }
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    val first =
      assertNotNull(fixture.state.styleAuthority.resolveMissingImage(fixture.adapter, "icon"))
    started.await()
    assertSame(first, fixture.state.styleAuthority.resolveMissingImage(fixture.adapter, "icon"))
    release.complete(Unit)
    first.await()
    assertEquals(1, calls)
    assertTrue(binding.imageExists("icon"))
    fixture.close()
  }

  @Test
  fun null_and_throwing_resolvers_are_not_retried_until_replaced() = runTest {
    val fixture = presentationFixture()
    val binding = RecordingStyleBinding()
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    for (throws in listOf(false, true)) {
      var calls = 0
      fixture.state.missingImageResolver = {
        calls++
        if (throws) error("cannot generate icon")
        null
      }
      repeat(3) {
        assertNotNull(fixture.state.styleAuthority.resolveMissingImage(fixture.adapter, "icon"))
          .await()
      }
      assertEquals(1, calls)
      assertFalse(binding.imageExists("icon"))
    }
    fixture.close()
  }

  @Test
  fun a_failed_image_add_can_be_retried() = runTest {
    val fixture = presentationFixture()
    val binding = RecordingStyleBinding(beforeAddImage = { error("add failed") })
    var calls = 0
    fixture.state.missingImageResolver = {
      calls++
      ResolvedStyleImage(PreparedImage.fromBitmap(FakeImageBitmap(1, 1)))
    }
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    assertNotNull(fixture.state.styleAuthority.resolveMissingImage(fixture.adapter, "icon")).await()
    assertFalse(binding.imageExists("icon"))
    assertNotNull(fixture.state.styleAuthority.resolveMissingImage(fixture.adapter, "icon")).await()
    assertTrue(binding.imageExists("icon"))
    assertEquals(2, calls)
    fixture.close()
  }

  @Test
  fun an_old_resolution_does_not_forget_a_replacement_resolution() = runTest {
    val fixture = presentationFixture()
    val binding = RecordingStyleBinding()
    val oldStarted = CompletableDeferred<Unit>()
    val oldRelease = CompletableDeferred<Unit>()
    val newStarted = CompletableDeferred<Unit>()
    val newRelease = CompletableDeferred<Unit>()
    fixture.state.missingImageResolver = {
      oldStarted.complete(Unit)
      oldRelease.await()
      ResolvedStyleImage(PreparedImage.fromBitmap(FakeImageBitmap(1, 1)))
    }
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    val old =
      assertNotNull(fixture.state.styleAuthority.resolveMissingImage(fixture.adapter, "icon"))
    oldStarted.await()
    fixture.state.missingImageResolver = {
      newStarted.complete(Unit)
      newRelease.await()
      ResolvedStyleImage(PreparedImage.fromBitmap(FakeImageBitmap(1, 1)))
    }
    val replacement =
      assertNotNull(fixture.state.styleAuthority.resolveMissingImage(fixture.adapter, "icon"))
    newStarted.await()
    oldRelease.complete(Unit)
    old.await()
    assertSame(
      replacement,
      fixture.state.styleAuthority.resolveMissingImage(fixture.adapter, "icon"),
    )
    newRelease.complete(Unit)
    replacement.await()
    assertTrue(binding.imageExists("icon"))
    fixture.close()
  }

  @Test
  fun a_style_reload_cancels_pending_image_resolution() = runTest {
    val fixture = presentationFixture()
    val oldBinding = RecordingStyleBinding()
    val newBinding = RecordingStyleBinding()
    val started = CompletableDeferred<Unit>()
    fixture.state.missingImageResolver = {
      started.complete(Unit)
      awaitCancellation()
    }
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, oldBinding)
    val old =
      assertNotNull(fixture.state.styleAuthority.resolveMissingImage(fixture.adapter, "icon"))
    started.await()
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, newBinding)
    assertFailsWith<CancellationException> { old.await() }

    fixture.state.missingImageResolver = {
      ResolvedStyleImage(PreparedImage.fromBitmap(FakeImageBitmap(1, 1)))
    }
    assertNotNull(fixture.state.styleAuthority.resolveMissingImage(fixture.adapter, "icon")).await()
    assertFalse(oldBinding.imageExists("icon"))
    assertTrue(newBinding.imageExists("icon"))
    fixture.close()
  }

  @Test
  fun a_replaced_resolver_leaves_the_resolution_in_flight_to_finish() = runTest {
    val fixture = presentationFixture()
    val binding = RecordingStyleBinding()
    val release = CompletableDeferred<Unit>()
    fixture.state.missingImageResolver = {
      release.await()
      ResolvedStyleImage(PreparedImage.fromBitmap(FakeImageBitmap(1, 1)))
    }
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    val resolution =
      assertNotNull(fixture.state.styleAuthority.resolveMissingImage(fixture.adapter, "icon"))

    fixture.state.missingImageResolver = { null }
    release.complete(Unit)
    resolution.await()

    assertTrue(
      binding.imageExists("icon") == true,
      "replacing the resolver abandoned the request that the engine made",
    )
    fixture.close()
  }

  @Test
  fun an_equal_resolver_replaces_the_current_one() = runTest {
    class EqualResolver(val image: ResolvedStyleImage?) : MissingImageResolver {
      override suspend fun resolve(request: MissingImageRequest): ResolvedStyleImage? = image

      override fun equals(other: Any?): Boolean = other is EqualResolver

      override fun hashCode(): Int = 0
    }
    val fixture = presentationFixture()
    val binding = RecordingStyleBinding()
    fixture.state.missingImageResolver = EqualResolver(null)
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.missingImageResolver =
      EqualResolver(ResolvedStyleImage(PreparedImage.fromBitmap(FakeImageBitmap(1, 1))))

    assertNotNull(fixture.state.styleAuthority.resolveMissingImage(fixture.adapter, "icon")).await()

    assertTrue(binding.imageExists("icon"))
    fixture.close()
  }

  @Test
  fun imperative_commands_cannot_mutate_composition_owned_resources() = runTest {
    val fixture = presentationFixture()
    val source = attributedVectorSource("owned", "owned attribution")
    val image = FakeImageBitmap(1, 1)
    val declaredRevision =
      StyleSnapshot(
        sources = listOf(source.definition()),
        layers = emptyList(),
        images =
          listOf(
            StyleImageDefinition(
              "owned",
              PreparedImage.fromBitmap(image),
              sdf = false,
              stretch = null,
            )
          ),
      )
    val binding = RecordingStyleBinding()
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.applyRevision(binding, declaredRevision)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)

    assertNull(fixture.state.style.sources["owned"]!!.asMutable)
    assertNull(fixture.state.style.images["owned"]!!.asMutable)
    assertTrue(binding.sourceExists("owned") == true)
    assertTrue(binding.imageExists("owned") == true)
    fixture.close()
  }

  @Test
  fun a_handle_read_across_a_close_returns_null_and_later_writes_throw() = runTest {
    val fixture = presentationFixture()
    val binding =
      QueuedOwnerStyleBinding(
        RecordingStyleBinding(layers = listOf(TestLayer("background", "background")))
      )
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
    val layer = assertNotNull(fixture.state.style.layers["background"]?.asMutable)
    binding.ownerBusy = true
    val read = async(start = CoroutineStart.UNDISPATCHED) { layer.getProperty("type") }

    // A close while the read waits for the owner reads like an expired handle.
    fixture.state.close()
    binding.runOwnerTasks()
    assertNull(read.await())
    // A write that starts after the close is misuse.
    assertFailsWith<IllegalStateException> {
      layer.setPaintProperty("background-opacity", JsonPrimitive(0.5))
    }
    fixture.runtime.close()
  }

  @Test
  fun closing_rejects_new_style_calls_and_reads_as_a_style_change_for_calls_in_flight() = runTest {
    val fixture = presentationFixture()
    val binding =
      QueuedOwnerStyleBinding(
        RecordingStyleBinding(layers = listOf(TestLayer("background", "background")))
      )
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
    val style = fixture.state.style
    val layer = assertNotNull(style.layers["background"])
    binding.ownerBusy = true
    val added =
      async(start = CoroutineStart.UNDISPATCHED) {
        style.sources.add(attributedVectorSource("added", "added"))
      }

    // A close while the add waits for the owner reads like a style change.
    fixture.state.close()
    binding.runOwnerTasks()
    assertNull(added.await())

    // A call that starts after the close is misuse.
    val closed = assertFailsWith<IllegalStateException> { layer.asMutable }
    assertEquals("The map state is closed", closed.message)
    assertFailsWith<IllegalStateException> { style.sources.add(attributedVectorSource("b", "b")) }
    assertFailsWith<IllegalStateException> { style.images.remove("icon") }
    assertFailsWith<IllegalStateException> { style.transition.set(TransitionOptions()) }
    assertFailsWith<IllegalStateException> { style.transition.get() }
    fixture.runtime.close()
  }

  @Test
  fun style_api_misuse_throws_standard_exceptions() = runTest {
    val fixture = presentationFixture()
    val binding = RecordingStyleBinding(layers = listOf(TestLayer("background", "background")))
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
    val baseStyle = fixture.state.style.asMutable!!
    val layer = fixture.state.style.layers["background"]!!.asMutable!!

    val fixed =
      assertFailsWith<IllegalArgumentException> {
        layer.setRootProperty("source", JsonPrimitive("other"))
      }
    assertEquals(
      "'source' is fixed for the generation of background layer 'background'",
      fixed.message,
    )
    fixture.state.style.baseStyleDeclared = true
    assertFailsWith<IllegalStateException> { baseStyle.baseStyle = BaseStyle.Json("replacement") }
    fixture.close()
  }

  @Test
  fun a_declarative_revision_can_declare_a_resolver_supplied_image_id() = runTest {
    val fixture = presentationFixture()
    val binding = RecordingStyleBinding()
    fixture.state.missingImageResolver = {
      ResolvedStyleImage(PreparedImage.fromBitmap(FakeImageBitmap(1, 1)))
    }
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
    assertNotNull(fixture.state.styleAuthority.resolveMissingImage(fixture.adapter, "shared"))
      .await()
    assertTrue(binding.imageExists("shared") == true)

    // A generated image ID can match one the resolver supplied; the declaration is not misuse.
    fixture.applyRevision(
      binding,
      StyleSnapshot(
        sources = emptyList(),
        layers = emptyList(),
        images =
          listOf(
            StyleImageDefinition(
              "shared",
              PreparedImage.fromBitmap(FakeImageBitmap(2, 2)),
              sdf = false,
              stretch = null,
            )
          ),
      ),
    )
    assertEquals(StyleLoadState.Ready, fixture.state.style.loadState)
    fixture.close()
  }

  @Test
  fun a_declarative_revision_cannot_claim_an_imperative_resource_id() = runTest {
    val fixture = presentationFixture()
    val binding = RecordingStyleBinding()
    val source = attributedVectorSource("shared", "shared attribution")
    val image = FakeImageBitmap(1, 1)
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)
    fixture.state.style.sources.add(source)

    assertFailsWith<IllegalStateException> {
      fixture.applyRevision(
        binding,
        StyleSnapshot(
          sources = listOf(source.definition()),
          layers = emptyList(),
          images = emptyList(),
        ),
      )
    }
    assertEquals(StyleLoadState.Ready, fixture.state.style.loadState)
    assertTrue(fixture.state.style.sources["shared"] != null)

    fixture.state.style.sources["shared"]!!.asMutable!!.remove()
    fixture.state.style.awaitCommands()
    fixture.state.style.setImage("shared", image)
    assertFailsWith<IllegalStateException> {
      fixture.applyRevision(
        binding,
        StyleSnapshot(
          sources = emptyList(),
          layers = emptyList(),
          images =
            listOf(
              StyleImageDefinition(
                "shared",
                PreparedImage.fromBitmap(image),
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
  fun attribution_derives_from_base_declarative_and_imperative_sources() = runTest {
    val fixture = presentationFixture()
    val base = attributedVectorSource("base", "base attribution")
    val declarative = attributedVectorSource("declarative", "declarative attribution")
    val declaredRevision =
      StyleSnapshot(
        sources = listOf(declarative.definition()),
        layers = emptyList(),
        images = emptyList(),
      )
    val binding = RecordingStyleBinding(sources = listOf(base))
    fixture.state.durableStyleCallbacks().onStyleChanged(fixture.adapter, binding)
    fixture.applyRevision(binding, declaredRevision)
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)

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
    fixture.state.durableStyleCallbacks().onStyleReady(fixture.adapter)

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
    state.durableStyleCallbacks().onStyleReady(adapter)
    state.style.sources.add(attributedVectorSource("retained", "retained attribution"))

    state.releasePresentation(token, adapter)
    testScheduler.advanceUntilIdle()

    assertTrue(state.style.sources["retained"] != null)
    state.style.sources["retained"]!!.asMutable!!.remove()
    state.style.awaitCommands()

    val replacement = RecordingStyleBinding()
    val replacementToken = state.reservePresentation()
    val replacementAdapter = RetainedAdapter(failOnClose = false)
    state.publishPresentation(replacementToken, replacementAdapter)
    state.durableStyleCallbacks().onStyleChanged(replacementAdapter, replacement)
    state.durableStyleCallbacks().onStyleReady(replacementAdapter)
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
        cameraPosition = initialCamera,
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
    assertNull(fixture.state.getVisibleBounds())
    assertNull(fixture.state.metersPerDpAtLatitude(0.0))
    fixture.close()
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

    val rendered = CameraPosition(target = Position(12.0, 34.0), zoom = 5.0)
    val viewport = testViewport().copy(cameraPosition = rendered)
    adapter.currentViewport = viewport
    adapter.lastCameraPosition = rendered
    state.lifecycle.seedCurrentPresentationViewport(adapter)

    assertEquals(viewport, state.viewport)
    assertEquals(rendered, state.cameraPosition)
    state.close()
    runtime.close()
  }

  @Test
  fun inline_queries_reject_results_from_a_detached_presentation() = runTest {
    val queries: List<suspend (MapState) -> Any> =
      listOf(
        { it.cameraForBounds(BoundingBox(Position(-1.0, -1.0), Position(1.0, 1.0))) },
        { it.cameraForCoordinates(listOf(Position(-1.0, -1.0), Position(1.0, 1.0))) },
        { it.queryRenderedFeatures(DpOffset.Zero) },
        { it.queryRenderedFeatures(DpRect(0.dp, 0.dp, 10.dp, 10.dp)) },
      )
    for (query in queries) {
      val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
      val state = runtime.createMapState(BaseStyle.Demo)
      val token = state.reservePresentation()
      val adapter =
        object : PresentationTestAdapter() {
          override suspend fun cameraForBounds(
            boundingBox: BoundingBox,
            bearing: Double,
            pitch: Double,
            cameraPadding: DpPadding?,
            fitPadding: DpPadding,
          ): CameraPosition {
            state.releasePresentation(token, this)
            return CameraPosition(zoom = 5.0)
          }

          override suspend fun cameraForGeometry(
            geometry: Geometry,
            bearing: Double,
            pitch: Double,
            cameraPadding: DpPadding?,
            fitPadding: DpPadding,
          ): CameraPosition {
            state.releasePresentation(token, this)
            return CameraPosition(zoom = 5.0)
          }

          override suspend fun queryRenderedFeatures(
            offset: DpOffset,
            layerIds: Set<String>?,
            predicate: CompiledExpression<BooleanValue>?,
          ): List<Feature<Geometry, JsonObject?>> {
            state.releasePresentation(token, this)
            return emptyList()
          }

          override suspend fun queryRenderedFeatures(
            rect: DpRect,
            layerIds: Set<String>?,
            predicate: CompiledExpression<BooleanValue>?,
          ): List<Feature<Geometry, JsonObject?>> {
            state.releasePresentation(token, this)
            return emptyList()
          }
        }
      try {
        state.publishPresentation(token, adapter)
        requireNotNull(state.currentMapAttachment).updateViewport(testViewport())
        // Both completion and invalidation are ready before runLeaseBound reaches select.
        assertFailsWith<CancellationException> { query(state) }
      } finally {
        state.close()
        state.awaitClosed()
        runtime.close()
      }
    }
  }

  @Test
  fun a_geometry_query_rejects_empty_input_before_waiting_for_an_attachment() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Demo)
    assertFailsWith<IllegalArgumentException> { state.cameraForCoordinates(emptyList()) }
    assertFailsWith<IllegalArgumentException> {
      state.cameraForGeometry(GeometryCollection(listOf(MultiPoint(emptyList()))))
    }
    state.close()
    state.awaitClosed()
    runtime.close()
  }

  @Test
  fun a_bounds_query_waits_for_an_attachment_and_its_viewport() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Demo)
    val query = async {
      state.cameraForBounds(BoundingBox(Position(-1.0, -1.0), Position(1.0, 1.0)))
    }
    testScheduler.runCurrent()
    assertFalse(query.isCompleted)

    val token = state.reservePresentation()
    val adapter = PresentationTestAdapter()
    state.publishPresentation(token, adapter)
    testScheduler.runCurrent()
    assertFalse(query.isCompleted)

    requireNotNull(state.currentMapAttachment).updateViewport(testViewport())
    assertEquals(adapter.lastCameraPosition, query.await())
    assertFalse(adapter.boundsFit.isCompleted)
    state.close()
    state.awaitClosed()
    runtime.close()
  }

  @Test
  fun detaching_cancels_a_bounds_query_waiting_for_a_viewport() = runTest {
    val fixture = presentationFixture()
    val query = async {
      fixture.state.cameraForBounds(BoundingBox(Position(-1.0, -1.0), Position(1.0, 1.0)))
    }
    testScheduler.runCurrent()
    assertFalse(query.isCompleted)
    fixture.state.releasePresentation(fixture.token, fixture.adapter)
    assertFailsWith<CancellationException> { query.await() }
    fixture.close()
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
  fun a_bounds_fit_is_cancelled_when_its_presentation_is_released_before_the_first_viewport() =
    runTest {
      val fixture = presentationFixture()
      val fit = async {
        fixture.state.fitCameraToBounds(BoundingBox(Position(-1.0, -1.0), Position(1.0, 1.0)))
      }
      testScheduler.runCurrent()
      assertFalse(fit.isCompleted)

      fixture.state.releasePresentation(fixture.token, fixture.adapter)
      assertFailsWith<CancellationException> { fit.await() }
      val replacement = PresentationTestAdapter()
      fixture.state.publishPresentation(fixture.state.reservePresentation(), replacement)
      requireNotNull(fixture.state.currentMapAttachment).updateViewport(testViewport())
      testScheduler.runCurrent()
      assertFalse(fixture.adapter.boundsFit.isCompleted)
      assertFalse(replacement.boundsFit.isCompleted)
      fixture.close()
    }

  @Test
  fun await_viewport_survives_a_replacement_while_parked_on_an_immediate_dispatcher() = runTest {
    val fixture = presentationFixture()
    // Main.immediate resumes parked callers inline on the disposing frame; Unconfined models that.
    val waiting = async(Dispatchers.Unconfined) { fixture.state.awaitViewport() }
    assertFalse(waiting.isCompleted)

    fixture.state.releasePresentation(fixture.token, fixture.adapter)
    assertFalse(waiting.isCompleted)
    val viewport = testViewport()
    fixture.state.publishPresentation(
      fixture.state.reservePresentation(),
      PresentationTestAdapter().apply { currentViewport = viewport },
    )
    assertEquals(viewport, waiting.await())
    fixture.close()
  }

  @Test
  fun a_rendered_query_issued_while_detached_waits_for_the_next_attachment() = runTest {
    val fixture = presentationFixture()
    fixture.state.releasePresentation(fixture.token, fixture.adapter)
    val replacement = PresentationTestAdapter()
    supervisorScope {
      val query = async { fixture.state.queryRenderedFeatures(DpOffset.Zero) }
      testScheduler.runCurrent()
      assertFalse(replacement.queryStarted.isCompleted)

      val token = fixture.state.reservePresentation()
      fixture.state.publishPresentation(token, replacement)
      requireNotNull(fixture.state.currentMapAttachment).updateViewport(testViewport())
      replacement.queryStarted.await()
      assertFalse(fixture.adapter.queryStarted.isCompleted)
      query.cancel()
    }
    fixture.close()
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
  fun animation_admission_preserves_other_camera_jobs() = runTest {
    val fixture = presentationFixture()
    fixture.attachment.updateViewport(testViewport())
    val first = async {
      fixture.state.animateCamera(
        CameraPosition(zoom = 2.0).toCameraUpdate(),
        CameraAnimation.Fly(1.seconds),
      )
    }
    fixture.adapter.animationStarted.await()
    val second = async {
      fixture.state.animateCamera(
        CameraPosition(zoom = 3.0).toCameraUpdate(),
        CameraAnimation.Fly(1.seconds),
      )
    }
    testScheduler.runCurrent()

    assertFalse(first.isCompleted)
    assertFalse(second.isCompleted)
    assertTrue(fixture.attachment.isValid)

    fixture.adapter.finishAnimation.complete(Unit)
    first.await()
    second.await()
    fixture.close()
  }

  @Test
  fun an_anchored_animation_waits_for_a_viewport_but_does_not_restart_after_detach() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Empty)
    val animation = async {
      state.animateCameraAround(CameraAnchor.Screen(DpOffset(10.dp, 10.dp)), zoom = 4.0)
    }
    testScheduler.runCurrent()
    assertFalse(animation.isCompleted)
    val token = state.reservePresentation()
    val first = PresentationTestAdapter()
    state.publishPresentation(token, first)
    testScheduler.runCurrent()
    assertFalse(first.animationStarted.isCompleted)
    requireNotNull(state.currentMapAttachment).updateViewport(testViewport())
    first.animationStarted.await()
    state.releasePresentation(token, first)
    testScheduler.runCurrent()
    assertTrue(animation.isCancelled)
    val replacement = PresentationTestAdapter()
    state.publishPresentation(state.reservePresentation(), replacement)
    requireNotNull(state.currentMapAttachment).updateViewport(testViewport())
    testScheduler.runCurrent()
    assertFalse(replacement.animationStarted.isCompleted)
    state.close()
    state.awaitClosed()
    runtime.close()
  }

  @Test
  fun concurrent_camera_calls_wait_for_a_viewport_and_cancel_on_detach() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Demo)
    val superseded = async {
      state.animateCamera(
        CameraPosition(zoom = 2.0).toCameraUpdate(),
        CameraAnimation.Fly(1.seconds),
      )
    }
    testScheduler.runCurrent()
    val animation = async {
      state.animateCamera(
        CameraPosition(zoom = 4.0).toCameraUpdate(),
        CameraAnimation.Fly(1.seconds),
      )
    }
    testScheduler.runCurrent()
    assertFalse(superseded.isCompleted)
    assertFalse(animation.isCompleted)

    val firstToken = state.reservePresentation()
    val first = PresentationTestAdapter()
    state.publishPresentation(firstToken, first)
    testScheduler.runCurrent()
    assertFalse(first.animationStarted.isCompleted)
    requireNotNull(state.currentMapAttachment).updateViewport(testViewport())
    first.animationStarted.await()

    state.releasePresentation(firstToken, first)
    testScheduler.runCurrent()
    assertTrue(animation.isCancelled)
    assertTrue(superseded.isCancelled)

    val replacementToken = state.reservePresentation()
    val replacement = PresentationTestAdapter()
    state.publishPresentation(replacementToken, replacement)
    testScheduler.runCurrent()
    assertFalse(replacement.animationStarted.isCompleted)
    requireNotNull(state.currentMapAttachment).updateViewport(testViewport())
    testScheduler.runCurrent()
    assertFalse(replacement.animationStarted.isCompleted)
    state.close()
    state.awaitClosed()
    runtime.close()
  }

  @Test
  fun concurrent_camera_commands_dispatch_in_admission_order_even_when_resumed_backwards() =
    runTest {
      class QueuedDispatcher : CoroutineDispatcher() {
        val pending = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
          pending.addLast(block)
        }

        fun drain() {
          while (pending.isNotEmpty()) pending.removeFirst().run()
        }
      }

      for (cancelMiddle in listOf(false, true)) {
        val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
        val state = runtime.createMapState(BaseStyle.Empty)
        val dispatched = mutableListOf<CameraUpdate>()
        val adapter =
          object : PresentationTestAdapter() {
            override suspend fun animateCamera(
              update: CameraUpdate,
              animation: CameraAnimation,
              guard: CameraCommandGuard?,
            ) {
              dispatched += update
              guard?.dispatched()
              finishAnimation.await()
            }
          }
        if (cancelMiddle) state.publishPresentation(state.reservePresentation(), adapter)
        val olderDispatcher = QueuedDispatcher()
        val newerDispatcher = QueuedDispatcher()
        val olderUpdate = CameraUpdate(zoom = 5.0, bearing = 90.0)
        val newerUpdate = CameraUpdate(zoom = 10.0)
        val older =
          async(olderDispatcher, start = CoroutineStart.UNDISPATCHED) {
            state.animateCamera(olderUpdate)
          }
        val middle =
          if (cancelMiddle)
            async(newerDispatcher, start = CoroutineStart.UNDISPATCHED) {
              state.animateCamera(CameraUpdate(pitch = 30.0))
            }
          else null
        val newer =
          async(newerDispatcher, start = CoroutineStart.UNDISPATCHED) {
            state.animateCamera(newerUpdate)
          }
        middle?.cancel()
        newerDispatcher.drain()
        if (!cancelMiddle) state.publishPresentation(state.reservePresentation(), adapter)
        requireNotNull(state.currentMapAttachment).updateViewport(testViewport())
        try {
          newerDispatcher.drain()
          assertTrue(dispatched.isEmpty(), "newer work must wait for the older dispatch")
          olderDispatcher.drain()
          newerDispatcher.drain()
          assertEquals(listOf(olderUpdate, newerUpdate), dispatched)
          assertFalse(older.isCompleted, "ordering must not wait for animation completion")
          assertFalse(newer.isCompleted)
        } finally {
          older.cancel()
          newer.cancel()
          olderDispatcher.drain()
          newerDispatcher.drain()
          state.close()
          state.awaitClosed()
          runtime.close()
        }
      }
    }

  @Test
  fun camera_takeover_cancels_calls_waiting_for_attachment_or_viewport() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Empty)
    val bounds = BoundingBox(Position(-1.0, -1.0), Position(1.0, 1.0))
    val animation = async { state.animateCamera(CameraPosition(zoom = 2.0).toCameraUpdate()) }
    testScheduler.runCurrent()
    state.setCameraPosition(CameraPosition(zoom = 3.0))
    testScheduler.runCurrent()
    assertTrue(animation.isCancelled)

    val fit = async { state.fitCameraToBounds(bounds) }
    testScheduler.runCurrent()
    val token = state.reservePresentation()
    val adapter = PresentationTestAdapter()
    state.publishPresentation(token, adapter)
    testScheduler.runCurrent()
    assertFalse(fit.isCompleted)

    val animatedFit = async { state.animateCameraToBounds(bounds) }
    testScheduler.runCurrent()
    assertTrue(fit.isCancelled)
    assertFalse(animatedFit.isCompleted)
    state.setCameraPosition(CameraPosition(zoom = 4.0))
    testScheduler.runCurrent()
    assertTrue(animatedFit.isCancelled)
    assertFalse(adapter.boundsFit.isCompleted)
    state.close()
    state.awaitClosed()
    runtime.close()
  }

  @Test
  fun a_queued_stop_cannot_cancel_a_newer_command() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Empty)
    var stopGuard: CameraCommandGuard? = null
    val adapter =
      object : PresentationTestAdapter() {
        override fun stopCameraMovement(guard: CameraCommandGuard) {
          stopGuard = guard
        }
      }
    val token = state.reservePresentation()
    state.publishPresentation(token, adapter)
    state.stopCameraMovement()
    val guard = assertNotNull(stopGuard)
    assertTrue(guard.isValid())
    requireNotNull(state.currentMapAttachment).updateViewport(testViewport())
    val animation = async { state.animateCamera(CameraPosition(zoom = 4.0).toCameraUpdate()) }
    testScheduler.runCurrent()
    assertFalse(guard.isValid())
    adapter.finishAnimation.complete(Unit)
    animation.await()
    state.close()
    state.awaitClosed()
    runtime.close()
  }

  @Test
  fun stopping_cancels_commands_before_attachment_and_before_a_viewport() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Empty)
    val position = CameraPosition(zoom = 3.0)
    state.setCameraPosition(position)
    val animation = async { state.animateCamera(CameraPosition(zoom = 8.0).toCameraUpdate()) }
    testScheduler.runCurrent()
    state.stopCameraMovement()
    testScheduler.runCurrent()
    assertTrue(animation.isCancelled)
    assertEquals(position, state.cameraPosition)

    val token = state.reservePresentation()
    val adapter = PresentationTestAdapter()
    state.publishPresentation(token, adapter)
    val fit = async {
      state.fitCameraToBounds(BoundingBox(Position(-1.0, -1.0), Position(1.0, 1.0)))
    }
    testScheduler.runCurrent()
    assertFalse(fit.isCompleted)
    state.stopCameraMovement()
    testScheduler.runCurrent()
    assertTrue(fit.isCancelled)
    assertFalse(adapter.boundsFit.isCompleted)
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
        state.animateCamera(
          CameraPosition(zoom = 4.0).toCameraUpdate(),
          CameraAnimation.Fly(1.seconds),
        )
      }
      testScheduler.runCurrent()

      state.close()

      assertFailsWith<CancellationException> { animation.await() }
    }
    state.awaitClosed()
    runtime.close()
  }
}

private fun attributedVectorSource(): VectorTileSource =
  attributedVectorSource("tiles", "attribution")

private fun attributedVectorSource(id: String, attribution: String): VectorTileSource =
  VectorTileSource(
    id = id,
    tiles = listOf("https://example.com/{z}/{x}/{y}.pbf"),
  ) {
    attributionHtml = attribution
  }

private val ImageQuad =
  PositionQuad(
    Position(-1.0, 1.0),
    Position(1.0, 1.0),
    Position(1.0, -1.0),
    Position(-1.0, -1.0),
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

private suspend fun PresentationFixture.applyRevision(
  binding: StyleBinding,
  revision: StyleSnapshot,
) {
  adapter.styleBinding = binding
  state.styleAuthority.applyStyleRevision(adapter, binding, revision)
}

private fun presentationFixture(
  adapter: PresentationTestAdapter = PresentationTestAdapter()
): PresentationFixture {
  val runtime = mapRuntimeForTest()
  val state = runtime.createMapState(BaseStyle.Demo)
  val token = state.reservePresentation()
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
) : PresentationTestAdapter(), RetainedEngineSteps {
  lateinit var lifecycle: RetainedEngineLifecycle
  val commands = mutableListOf<String>()

  override val retainsEngineBetweenPresentations = true
  override val presentationCompatibilityKey: Any = Any()

  override val isClosing: Boolean
    get() = lifecycle.isClosing

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
    lifecycle.detach()
  }

  override fun close() = lifecycle.close()

  override suspend fun awaitClosed() = lifecycle.awaitClosed()
}

private class ClosingDuringConfigurationAdapter(private val closeState: () -> Unit) :
  PresentationTestAdapter() {
  private var closed = false

  override fun setCameraPosition(cameraPosition: CameraPosition, guard: CameraCommandGuard?) {
    super.setCameraPosition(cameraPosition, guard)
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

  override fun setCameraPosition(cameraPosition: CameraPosition, guard: CameraCommandGuard?) {
    super.setCameraPosition(cameraPosition, guard)
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

  override suspend fun detachPresentation() {
    close()
    awaitClosed()
  }

  override suspend fun animateCamera(
    update: CameraUpdate,
    animation: CameraAnimation,
    guard: CameraCommandGuard?,
  ) {
    animationStarted.complete(Unit)
    guard?.dispatched()
    finishAnimation.await()
  }

  override suspend fun animateCameraAround(
    anchor: CameraAnchor,
    zoom: Double?,
    bearing: Double?,
    pitch: Double?,
    animation: CameraAnimation.Ease,
    guard: CameraCommandGuard?,
  ) {
    animationStarted.complete(Unit)
    guard?.dispatched()
    finishAnimation.await()
  }

  override suspend fun animateCameraToBounds(
    boundingBox: BoundingBox,
    bearing: Double,
    pitch: Double,
    cameraPadding: DpPadding?,
    fitPadding: DpPadding,
    animation: CameraAnimation,
    guard: CameraCommandGuard?,
  ) {
    guard?.dispatched()
    awaitCancellation()
  }

  override fun setBaseStyle(style: BaseStyle) {
    presentationWasVisibleWhileConfiguring =
      presentationWasVisibleWhileConfiguring || currentAttachment() != null
  }

  var styleBinding: StyleBinding = RecordingStyleBinding()
  private val styleReconciler = StyleReconciler()

  override suspend fun <T> reconcileStyleRevision(
    revision: StyleSnapshot,
    capture: (StyleBinding) -> T,
  ): T =
    checkNotNull(
      styleBinding.awaitOwner {
        styleReconciler.apply(styleBinding, revision)
        capture(styleBinding)
      }
    )

  override fun getCameraPosition(): CameraPosition = lastCameraPosition

  override fun getCameraConstraints(): CameraConstraints = CameraConstraints()

  override fun setCameraPosition(cameraPosition: CameraPosition, guard: CameraCommandGuard?) {
    presentationWasVisibleWhileConfiguring =
      presentationWasVisibleWhileConfiguring || currentAttachment() != null
    lastCameraPosition = cameraPosition
  }

  override fun stopCameraMovement(guard: CameraCommandGuard) = Unit

  override fun setViewportInsets(insets: PaddingValues) = Unit

  override suspend fun cameraForBounds(
    boundingBox: BoundingBox,
    bearing: Double,
    pitch: Double,
    cameraPadding: DpPadding?,
    fitPadding: DpPadding,
  ): CameraPosition = lastCameraPosition

  override suspend fun cameraForGeometry(
    geometry: Geometry,
    bearing: Double,
    pitch: Double,
    cameraPadding: DpPadding?,
    fitPadding: DpPadding,
  ): CameraPosition = lastCameraPosition

  override suspend fun fitCameraToBounds(
    boundingBox: BoundingBox,
    bearing: Double,
    pitch: Double,
    cameraPadding: DpPadding?,
    fitPadding: DpPadding,
    guard: CameraCommandGuard?,
  ) {
    boundsFit.complete(Unit)
  }

  override fun setCameraConstraints(value: CameraConstraints) = Unit

  override fun getVisibleBounds(): VisibleBounds =
    VisibleBounds(Position(-1.0, -1.0), Position(1.0, 1.0))

  override fun getVisibleRegion(): VisibleRegion =
    VisibleRegion(
      farLeft = Position(-1.0, 1.0),
      farRight = Position(1.0, 1.0),
      nearLeft = Position(-1.0, -1.0),
      nearRight = Position(1.0, -1.0),
    )

  override fun getViewport(): Viewport? {
    viewportReads++
    // An engine renders its viewport with the camera it holds.
    return currentViewport?.copy(cameraPosition = lastCameraPosition)
  }

  override fun setRenderSettings(value: RenderOptions) = Unit

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
    cameraPosition = CameraPosition(),
    size = DpSize(100.dp, 100.dp),
    visibleBounds = VisibleBounds(Position(-1.0, -1.0), Position(1.0, 1.0)),
    visibleRegion =
      VisibleRegion(
        farLeft = Position(-1.0, 1.0),
        farRight = Position(1.0, 1.0),
        nearLeft = Position(-1.0, -1.0),
        nearRight = Position(1.0, -1.0),
      ),
    metersPerDpAtTarget = 1.0,
  )
