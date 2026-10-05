package org.maplibre.compose.map

import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.GeoJsonSourceHandle
import org.maplibre.compose.sources.TileSetOptions
import org.maplibre.compose.sources.VectorTileSource
import org.maplibre.compose.sources.VectorTileSourceHandle
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.RecordingStyleBinding
import org.maplibre.compose.style.StyleHandleException
import org.maplibre.compose.style.StyleReconciler
import org.maplibre.compose.style.StyleSnapshot
import org.maplibre.compose.testing.setImage

class MapSnapshotterTest {

  @Test
  fun snapshot_requests_reject_invalid_pixel_density_and_font_scale() {
    for (value in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
      assertFailsWith<IllegalArgumentException> {
        MapSnapshotRequest(1, 1, density = Density(value))
      }
      assertFailsWith<IllegalArgumentException> {
        MapSnapshotRequest(1, 1, density = Density(1f, fontScale = value))
      }
    }
  }

  @Test
  fun captures_execute_in_submission_order() = runTest {
    val started = Channel<MapSnapshotRequest>(Channel.UNLIMITED)
    val finish = Channel<Unit>(Channel.UNLIMITED)
    val firstImage = FakeImageBitmap(1, 1)
    val secondImage = FakeImageBitmap(2, 1)
    var captureIndex = 0
    val adapter =
      FakeSnapshotterAdapter(
        capture = { request, _ ->
          started.send(request)
          finish.receive()
          if (captureIndex++ == 0) firstImage else secondImage
        }
      )
    val runtime =
      mapRuntimeForTest(
        createSnapshotterAdapter = { adapter },
        styleEvaluator = StyleCompositionEvaluator { _, _, _, _, _, _ -> StyleSnapshot.Empty },
      )
    val snapshotter = runtime.createSnapshotter(BaseStyle.Empty)
    val firstRequest = MapSnapshotRequest(width = 20, height = 10)
    val secondRequest = MapSnapshotRequest(width = 40, height = 30)

    val first = async { snapshotter.capture(firstRequest) }
    val second = async { snapshotter.capture(secondRequest) }

    assertSame(firstRequest, started.receive())
    assertFalse(started.tryReceive().isSuccess)
    finish.send(Unit)
    assertSame(secondRequest, started.receive())
    finish.send(Unit)
    assertSame(firstImage, first.await())
    assertSame(secondImage, second.await())

    snapshotter.close()
    snapshotter.awaitClosed()
    runtime.close()
    runtime.awaitClosed()
  }

  @Test
  fun capture_work_runs_in_the_runtime_physical_scope() = runTest {
    var captureContext: String? = null
    val adapter =
      FakeSnapshotterAdapter(
        capture = { request, _ ->
          captureContext = currentCoroutineContext()[CoroutineName]?.name
          FakeImageBitmap(request.width, request.height)
        }
      )
    val runtime =
      mapRuntimeForTest(
        physicalScope =
          CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("physical")),
        createSnapshotterAdapter = { adapter },
        styleEvaluator = StyleCompositionEvaluator { _, _, _, _, _, _ -> StyleSnapshot.Empty },
      )
    val snapshotter = runtime.createSnapshotter(BaseStyle.Empty)

    withContext(CoroutineName("caller")) { snapshotter.capture(MapSnapshotRequest(1, 1)) }

    assertEquals("physical", captureContext)
    close(snapshotter, runtime)
  }

  @Test
  fun a_published_snapshot_style_accepts_imperative_source_and_image_commands() = runTest {
    val binding = RecordingStyleBinding()
    val runtime =
      mapRuntimeForTest(
        createSnapshotterAdapter = { FakeSnapshotterAdapter(prepare = { _, _ -> binding }) },
        styleEvaluator = StyleCompositionEvaluator { _, _, _, _, _, _ -> StyleSnapshot.Empty },
      )
    val snapshotter = runtime.createSnapshotter(BaseStyle.Empty)
    val source =
      GeoJsonSource(
        id = "imperative",
        data = GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[]}"""),
        options = GeoJsonOptions(),
      )

    withContext(Dispatchers.Unconfined) {
      snapshotter.capture(MapSnapshotRequest(1, 1))
      val sourceHandle = snapshotter.style.sources.add(source)
      assertEquals("imperative", sourceHandle.id)
      assertTrue(snapshotter.style.sources["imperative"] is GeoJsonSourceHandle)
      val imageHandle = snapshotter.style.setImage("imperative", FakeImageBitmap(1, 1))
      assertEquals(setOf("imperative"), binding.imageIds)
      imageHandle.remove()
      snapshotter.style.awaitCommands()
      sourceHandle.remove()
      snapshotter.style.awaitCommands()
      assertTrue(snapshotter.style.sources.none())
      val currentSource = snapshotter.style.sources.add(source)
      val currentImage = snapshotter.style.setImage("imperative", FakeImageBitmap(1, 1))
      assertFailsWith<StyleHandleException> { sourceHandle.remove() }
      assertFailsWith<StyleHandleException> { imageHandle.remove() }
      assertTrue(binding.sourceExists("imperative") == true)
      assertEquals(setOf("imperative"), binding.imageIds)
      snapshotter.style.asMutable!!.baseStyle =
        BaseStyle.Json("""{"version":8,"sources":{},"layers":[]}""")
      assertFailsWith<StyleHandleException> { currentSource.remove() }
      assertFailsWith<StyleHandleException> { currentImage.remove() }
    }

    close(snapshotter, runtime)
  }

  @Test
  fun reused_snapshot_style_preserves_existing_resource_handles() = runTest {
    val source =
      GeoJsonSource(
        id = "base-source",
        data = GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[]}"""),
        options = GeoJsonOptions(),
      )
    val binding =
      RecordingStyleBinding(
        sources = listOf(source),
        layers = listOf(TestLayer("base-layer", "background")),
      )
    val runtime =
      mapRuntimeForTest(
        createSnapshotterAdapter = { FakeSnapshotterAdapter(prepare = { _, _ -> binding }) },
        styleEvaluator = StyleCompositionEvaluator { _, _, _, _, _, _ -> StyleSnapshot.Empty },
      )
    val snapshotter = runtime.createSnapshotter(BaseStyle.Empty)
    val request = MapSnapshotRequest(1, 1)
    snapshotter.capture(request)
    val sourceHandle = checkNotNull(snapshotter.style.sources["base-source"])
    val layerHandle = checkNotNull(snapshotter.style.layers["base-layer"])

    snapshotter.capture(request)

    assertSame(sourceHandle, snapshotter.style.sources["base-source"])
    assertSame(layerHandle, snapshotter.style.layers["base-layer"])
    assertEquals("", sourceHandle.attributionHtml)
    assertNull(layerHandle.getProperty("background-opacity"))
    snapshotter.style.asMutable!!.baseStyle =
      BaseStyle.Json("""{"version":8,"sources":{},"layers":[]}""")
    assertFailsWith<StyleHandleException> { sourceHandle.asMutable }
    assertFailsWith<StyleHandleException> { layerHandle.getProperty("background-opacity") }
    close(snapshotter, runtime)
  }

  @Test
  fun reused_snapshot_style_invalidates_a_structurally_replaced_source() = runTest {
    val original = attributedVectorSource("original")
    val replacement = attributedVectorSource("replacement")
    var desired = StyleSnapshot(listOf(original.definition()), emptyList(), emptyList())
    val binding = RecordingStyleBinding()
    val reconciler = StyleReconciler()
    val runtime =
      mapRuntimeForTest(
        createSnapshotterAdapter = {
          FakeSnapshotterAdapter(
            prepare = { _, _ -> binding },
            capture = { request, revision ->
              reconciler.apply(binding, revision)
              FakeImageBitmap(request.width, request.height)
            },
          )
        },
        styleEvaluator = StyleCompositionEvaluator { _, _, _, _, _, _ -> desired },
      )
    val snapshotter = runtime.createSnapshotter(BaseStyle.Empty)
    val request = MapSnapshotRequest(1, 1)
    snapshotter.capture(request)
    val stale = assertIs<VectorTileSourceHandle>(snapshotter.style.sources["shared"])

    desired = StyleSnapshot(listOf(replacement.definition()), emptyList(), emptyList())
    snapshotter.capture(request)

    assertFailsWith<StyleHandleException> { stale.resetFeatureStates("layer") }
    assertEquals("replacement", snapshotter.style.sources["shared"]?.attributionHtml)
    close(snapshotter, runtime)
  }

  @Test
  fun imperative_commands_cannot_cross_an_active_snapshot_style_revision() = runTest {
    val binding = RecordingStyleBinding()
    val captureStarted = CompletableDeferred<Unit>()
    val finishCapture = CompletableDeferred<Unit>()
    var blockCapture = false
    val runtime =
      mapRuntimeForTest(
        createSnapshotterAdapter = {
          FakeSnapshotterAdapter(
            prepare = { _, _ -> binding },
            capture = { _, _ ->
              if (blockCapture) {
                captureStarted.complete(Unit)
                finishCapture.await()
              }
              FakeImageBitmap(1, 1)
            },
          )
        },
        styleEvaluator = StyleCompositionEvaluator { _, _, _, _, _, _ -> StyleSnapshot.Empty },
      )
    val snapshotter = runtime.createSnapshotter(BaseStyle.Empty)
    snapshotter.capture(MapSnapshotRequest(1, 1))
    val handle = snapshotter.style.sources.add(attributedVectorSource("imperative"))
    blockCapture = true
    val capture = async { snapshotter.capture(MapSnapshotRequest(1, 1)) }
    captureStarted.await()

    assertFailsWith<StyleHandleException> { handle.resetFeatureStates("layer") }
    assertFailsWith<StyleHandleException> {
      snapshotter.style.setImage("crossing", FakeImageBitmap(1, 1))
    }

    finishCapture.complete(Unit)
    capture.await()
    assertTrue(binding.imageIds.isEmpty())
    close(snapshotter, runtime)
  }

  @Test
  fun queued_cancellation_removes_only_that_request() = runTest {
    val started = Channel<MapSnapshotRequest>(Channel.UNLIMITED)
    val finish = Channel<Unit>(Channel.UNLIMITED)
    var cancellationRequests = 0
    val adapter =
      FakeSnapshotterAdapter(
        capture = { request, _ ->
          started.send(request)
          finish.receive()
          FakeImageBitmap(request.width, request.height)
        },
        cancel = {
          cancellationRequests++
          SnapshotterEngineDisposition.Retained
        },
      )
    val runtime = runtimeWith(adapter)
    val snapshotter = runtime.createSnapshotter(BaseStyle.Empty)
    val firstRequest = MapSnapshotRequest(10, 10)
    val removedRequest = MapSnapshotRequest(20, 20)
    val thirdRequest = MapSnapshotRequest(30, 30)

    val first = async { snapshotter.capture(firstRequest) }
    val removed = async { snapshotter.capture(removedRequest) }
    val third = async { snapshotter.capture(thirdRequest) }
    assertSame(firstRequest, started.receive())

    removed.cancelAndJoin()
    finish.send(Unit)

    assertSame(thirdRequest, started.receive())
    finish.send(Unit)
    first.await()
    third.await()
    assertEquals(0, cancellationRequests)
    close(snapshotter, runtime)
  }

  @Test
  @OptIn(ExperimentalCoroutinesApi::class)
  fun active_cancellation_delays_the_next_request_until_platform_cleanup() = runTest {
    for (duringPreparation in listOf(true, false)) {
      val started = Channel<MapSnapshotRequest>(Channel.UNLIMITED)
      val cleanupStarted = CompletableDeferred<Unit>()
      val releaseCleanup = CompletableDeferred<Unit>()
      val image = FakeImageBitmap(1, 1)
      suspend fun enterStage(request: MapSnapshotRequest) {
        started.send(request)
        if (request.width == 1) awaitCancellation()
      }
      val adapter =
        FakeSnapshotterAdapter(
          prepare = { _, request ->
            if (duringPreparation) enterStage(request)
            RecordingStyleBinding()
          },
          capture = { request, _ ->
            if (!duringPreparation) enterStage(request)
            image
          },
          cancel = {
            cleanupStarted.complete(Unit)
            releaseCleanup.await()
            SnapshotterEngineDisposition.Retained
          },
        )
      val runtime =
        mapRuntimeForTest(
          physicalScope = backgroundScope,
          createSnapshotterAdapter = { adapter },
          styleEvaluator = StyleCompositionEvaluator { _, _, _, _, _, _ -> StyleSnapshot.Empty },
        )
      val snapshotter = runtime.createSnapshotter(BaseStyle.Empty)
      val activeRequest = MapSnapshotRequest(1, 1)
      val nextRequest = MapSnapshotRequest(2, 2)
      val active = async { snapshotter.capture(activeRequest) }
      val next = async { snapshotter.capture(nextRequest) }
      try {
        assertSame(activeRequest, started.receive())
        active.cancelAndJoin()
        cleanupStarted.await()
        runCurrent()

        assertFalse(started.tryReceive().isSuccess)
        releaseCleanup.complete(Unit)
        assertSame(nextRequest, started.receive())
        assertSame(image, next.await())
      } finally {
        releaseCleanup.complete(Unit)
        close(snapshotter, runtime)
      }
    }
  }

  @Test
  @OptIn(ExperimentalCoroutinesApi::class)
  fun active_cancellation_marks_the_style_pending_when_the_engine_is_retained() = runTest {
    val captureStarted = CompletableDeferred<Unit>()
    val image = FakeImageBitmap(1, 1)
    val initialBinding = RecordingStyleBinding()
    val adapter =
      FakeSnapshotterAdapter(
        // A capture after the cancellation loads a fresh style, as the engine does.
        prepare = { _, _ ->
          if (initialBinding.isLoaded) initialBinding else RecordingStyleBinding()
        },
        capture = { request, _ ->
          if (request.width == 2) {
            captureStarted.complete(Unit)
            awaitCancellation()
          }
          image
        },
        cancel = {
          SnapshotterEngineDisposition.Retained
        },
      )
    val runtime =
      mapRuntimeForTest(
        physicalScope = this,
        createSnapshotterAdapter = { adapter },
        styleEvaluator = StyleCompositionEvaluator { _, _, _, _, _, _ -> StyleSnapshot.Empty },
      )
    val snapshotter = runtime.createSnapshotter(BaseStyle.Empty)

    snapshotter.capture(MapSnapshotRequest(1, 1))
    assertEquals(StyleLoadState.Ready, snapshotter.style.loadState)
    val active = async { snapshotter.capture(MapSnapshotRequest(2, 2)) }
    captureStarted.await()

    active.cancelAndJoin()
    runCurrent()

    assertFalse(initialBinding.isLoaded)
    assertEquals(StyleLoadState.Pending, snapshotter.style.loadState)
    snapshotter.capture(MapSnapshotRequest(3, 3))
    assertEquals(StyleLoadState.Ready, snapshotter.style.loadState)
    close(snapshotter, runtime)
  }

  @Test
  @OptIn(ExperimentalCoroutinesApi::class)
  fun canceled_capture_cannot_republish_its_style_after_cleanup() = runTest {
    val captureStarted = CompletableDeferred<Unit>()
    val allowCaptureReturn = CompletableDeferred<Unit>()
    val image = FakeImageBitmap(1, 1)
    val binding = RecordingStyleBinding()
    val adapter =
      FakeSnapshotterAdapter(
        prepare = { _, _ -> binding },
        capture = { _, _ ->
          captureStarted.complete(Unit)
          try {
            awaitCancellation()
          } catch (_: CancellationException) {
            withContext(NonCancellable) { allowCaptureReturn.await() }
            image
          }
        },
        cancel = {
          allowCaptureReturn.complete(Unit)
          SnapshotterEngineDisposition.Retained
        },
      )
    val runtime =
      mapRuntimeForTest(
        physicalScope = this,
        createSnapshotterAdapter = { adapter },
        styleEvaluator = StyleCompositionEvaluator { _, _, _, _, _, _ -> StyleSnapshot.Empty },
      )
    val snapshotter = runtime.createSnapshotter(BaseStyle.Empty)
    val active = async { snapshotter.capture(MapSnapshotRequest(1, 1)) }
    captureStarted.await()

    active.cancelAndJoin()
    runCurrent()

    assertFalse(binding.isLoaded)
    assertEquals(StyleLoadState.Pending, snapshotter.style.loadState)
    close(snapshotter, runtime)
  }

  @Test
  fun stale_capture_failure_does_not_claim_a_newer_base_style() = runTest {
    val prepareStarted = CompletableDeferred<Unit>()
    val releaseFailure = CompletableDeferred<Unit>()
    val requestedStyles = mutableListOf<BaseStyle>()
    var preparations = 0
    val adapter =
      FakeSnapshotterAdapter(
        prepare = { baseStyle, _ ->
          requestedStyles += baseStyle
          if (preparations++ == 0) {
            prepareStarted.complete(Unit)
            releaseFailure.await()
            error("stale load failed")
          }
          RecordingStyleBinding()
        }
      )
    val runtime = runtimeWith(adapter)
    val initialStyle = BaseStyle.Json("""{"version":8,"sources":{},"layers":[]}""")
    val replacementStyle = BaseStyle.Json("""{"version":8,"sources":{},"layers":[] }""")
    val snapshotter = runtime.createSnapshotter(initialStyle)
    val staleResult = async { runCatching { snapshotter.capture(MapSnapshotRequest(1, 1)) } }
    prepareStarted.await()

    snapshotter.style.asMutable!!.baseStyle = replacementStyle
    releaseFailure.complete(Unit)
    assertTrue(staleResult.await().isFailure)
    assertEquals(StyleLoadState.Pending, snapshotter.style.loadState)

    snapshotter.capture(MapSnapshotRequest(1, 1))
    assertEquals(listOf<BaseStyle>(initialStyle, replacementStyle), requestedStyles)
    assertEquals(StyleLoadState.Ready, snapshotter.style.loadState)
    close(snapshotter, runtime)
  }

  @Test
  fun standard_exceptions_after_validation_are_snapshot_failures() = runTest {
    val prepareFailure = UnsupportedOperationException("offscreen renderer initialization failed")
    val styleFailure = IllegalArgumentException("base style layer not found")
    val cases =
      listOf(
        prepareFailure to
          runtimeWith(FakeSnapshotterAdapter(prepare = { _, _ -> throw prepareFailure })),
        styleFailure to
          runtimeWith(
            FakeSnapshotterAdapter(),
            StyleCompositionEvaluator { _, _, _, _, _, _ -> throw styleFailure },
          ),
      )

    cases.forEach { (cause, runtime) ->
      val snapshotter = runtime.createSnapshotter(BaseStyle.Empty)

      val failure =
        assertFailsWith<MapSnapshotException> {
          snapshotter.capture(MapSnapshotRequest(1, 1))
        }

      assertTrue(generateSequence(failure as Throwable?) { it.cause }.any { it === cause })
      close(snapshotter, runtime)
    }
  }

  @Test
  fun capture_preserves_platform_unavailability() = runTest {
    val runtime = mapRuntimeForTest()
    val snapshotter = runtime.createSnapshotter(BaseStyle.Empty)

    assertFailsWith<UnsupportedOperationException> {
      snapshotter.capture(MapSnapshotRequest(1, 1))
    }

    close(snapshotter, runtime)
  }

  @Test
  fun capture_does_not_wrap_fatal_errors() = runTest {
    val cause = FatalSnapshotError()
    val adapter = FakeSnapshotterAdapter(capture = { _, _ -> throw cause })
    val runtime = runtimeWith(adapter)
    val snapshotter = runtime.createSnapshotter(BaseStyle.Empty)

    val failure =
      assertFailsWith<FatalSnapshotError> {
        snapshotter.capture(MapSnapshotRequest(1, 1))
      }

    assertSame(cause, failure)
    close(snapshotter, runtime)
  }

  @Test
  fun style_invalidation_failure_is_reported_without_stalling_close() = runTest {
    val cause = IllegalStateException("style invalidation failed")
    val binding = RecordingStyleBinding(onInvalidate = { throw cause })
    val runtime = runtimeWith(FakeSnapshotterAdapter(prepare = { _, _ -> binding }))
    val snapshotter = runtime.createSnapshotter(BaseStyle.Empty)
    snapshotter.capture(MapSnapshotRequest(1, 1))

    snapshotter.close()
    val reported = assertFailsWith<MapCleanupException> { snapshotter.awaitClosed() }

    assertEquals(listOf(cause), reported.failures)
    runtime.close()
    runtime.awaitClosed()
  }

  @Test
  fun cleanup_failures_do_not_stall_the_queue_and_are_all_reported_on_close() = runTest {
    val firstStarted = CompletableDeferred<Unit>()
    val cleanupFailure = IllegalStateException("terminal cleanup failed")
    val closeFailure = IllegalStateException("adapter close failed")
    val nextImage = FakeImageBitmap(2, 2)
    val adapter =
      FakeSnapshotterAdapter(
        capture = { request, _ ->
          if (request.width == 1) {
            firstStarted.complete(Unit)
            awaitCancellation()
          }
          nextImage
        },
        cancel = { throw cleanupFailure },
        close = { throw closeFailure },
      )
    val runtime = runtimeWith(adapter)
    val snapshotter = runtime.createSnapshotter(BaseStyle.Empty)
    val active = async { snapshotter.capture(MapSnapshotRequest(1, 1)) }
    val next = async { snapshotter.capture(MapSnapshotRequest(2, 2)) }
    firstStarted.await()

    active.cancelAndJoin()
    assertSame(nextImage, next.await())
    snapshotter.close()
    val reported = assertFailsWith<MapCleanupException> { snapshotter.awaitClosed() }
    assertEquals(2, reported.failures.size)
    assertSame(cleanupFailure, reported.failures[0])
    assertSame(closeFailure, reported.failures[1])
    runtime.close()
    runtime.awaitClosed()
  }

  @Test
  fun closure_cancels_work_before_style_cleanup_and_waits_for_active_cleanup() = runTest {
    supervisorScope {
      val started = CompletableDeferred<Unit>()
      val releaseCleanup = CompletableDeferred<Unit>()
      var callerCancelled = false
      var cancelledBeforeInvalidation = false
      val binding =
        RecordingStyleBinding(onInvalidate = { cancelledBeforeInvalidation = callerCancelled })
      var captureCount = 0
      val adapter =
        FakeSnapshotterAdapter(
          prepare = { _, _ -> binding },
          capture = { request, _ ->
            if (captureCount++ == 0) FakeImageBitmap(request.width, request.height)
            else {
              started.complete(Unit)
              awaitCancellation()
            }
          },
          cancel = {
            releaseCleanup.await()
            SnapshotterEngineDisposition.Retained
          },
        )
      val runtime = runtimeWith(adapter)
      val snapshotter = runtime.createSnapshotter(BaseStyle.Empty)
      snapshotter.capture(MapSnapshotRequest(1, 1))
      val active =
        async(Dispatchers.Unconfined) {
          try {
            snapshotter.capture(MapSnapshotRequest(2, 2))
          } catch (error: CancellationException) {
            callerCancelled = true
            throw error
          }
        }
      val queued = async(Dispatchers.Unconfined) { snapshotter.capture(MapSnapshotRequest(3, 3)) }
      started.await()

      snapshotter.close()
      val closure = async { snapshotter.awaitClosed() }

      assertFailsWith<CancellationException> { queued.await() }
      assertFailsWith<CancellationException> { active.await() }
      assertFailsWith<IllegalStateException> {
        snapshotter.capture(MapSnapshotRequest(3, 3))
      }
      assertFalse(closure.isCompleted)
      releaseCleanup.complete(Unit)
      closure.await()
      assertTrue(cancelledBeforeInvalidation)
      runtime.close()
      runtime.awaitClosed()
    }
  }

  private fun runtimeWith(
    adapter: SnapshotterAdapter,
    styleEvaluator: StyleCompositionEvaluator = StyleCompositionEvaluator { _, _, _, _, _, _ ->
      StyleSnapshot.Empty
    },
  ): MapRuntime =
    mapRuntimeForTest(
      createSnapshotterAdapter = { adapter },
      styleEvaluator = styleEvaluator,
    )

  private suspend fun close(snapshotter: MapSnapshotter, runtime: MapRuntime) {
    snapshotter.close()
    snapshotter.awaitClosed()
    runtime.close()
    runtime.awaitClosed()
  }

  private fun attributedVectorSource(attribution: String): VectorTileSource =
    VectorTileSource(
      id = "shared",
      tiles = listOf("https://example.com/{z}/{x}/{y}.pbf"),
      options = TileSetOptions(attributionHtml = attribution),
    )

  private class FatalSnapshotError : Error("fatal snapshot failure")
}
