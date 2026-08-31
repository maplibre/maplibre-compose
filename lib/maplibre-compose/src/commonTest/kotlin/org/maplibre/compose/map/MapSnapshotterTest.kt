package org.maplibre.compose.map

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.RecordingStyleBinding
import org.maplibre.compose.style.StyleBinding

class MapSnapshotterTest {

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
        snapshotterAdapterFactory = SnapshotterAdapterFactory { adapter },
        styleEvaluator = StyleCompositionEvaluator { _, _, _, _, _ -> DesiredStyleRevision.Empty },
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
  fun every_capture_evaluates_current_state_with_its_own_environment() = runTest {
    var externalState = "first"
    val evaluations = mutableListOf<Triple<String, Float, LayoutDirection>>()
    val requests = mutableListOf<MapSnapshotRequest>()
    var adapterCreations = 0
    val image = FakeImageBitmap(1, 1)
    val adapter =
      FakeSnapshotterAdapter(
        capture = { request, _ ->
          requests += request
          image
        }
      )
    val runtime =
      mapRuntimeForTest(
        snapshotterAdapterFactory =
          SnapshotterAdapterFactory {
            adapterCreations++
            adapter
          },
        styleEvaluator =
          StyleCompositionEvaluator { _, _, density, layoutDirection, _ ->
            evaluations += Triple(externalState, density.density, layoutDirection)
            DesiredStyleRevision.Empty
          },
      )
    val snapshotter = runtime.createSnapshotter(BaseStyle.Empty)
    val first =
      MapSnapshotRequest(
        width = 10,
        height = 10,
        density = 2f,
        layoutDirection = LayoutDirection.Ltr,
      )
    val second =
      MapSnapshotRequest(
        width = 30,
        height = 20,
        density = 3f,
        layoutDirection = LayoutDirection.Rtl,
      )

    snapshotter.capture(first)
    externalState = "second"
    snapshotter.capture(second)

    assertEquals(
      listOf(
        Triple("first", 2f, LayoutDirection.Ltr),
        Triple("second", 3f, LayoutDirection.Rtl),
      ),
      evaluations,
    )
    assertEquals(listOf(first, second), requests)
    assertEquals(1, adapterCreations)
    snapshotter.close()
    snapshotter.awaitClosed()
    runtime.close()
    runtime.awaitClosed()
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
  fun cancellation_during_prepare_delays_the_next_capture_until_platform_cleanup() = runTest {
    val started = Channel<MapSnapshotRequest>(Channel.UNLIMITED)
    val cleanupStarted = CompletableDeferred<Unit>()
    val releaseCleanup = CompletableDeferred<Unit>()
    val image = FakeImageBitmap(1, 1)
    val adapter =
      FakeSnapshotterAdapter(
        prepare = { _, request ->
          started.send(request)
          if (request.width == 1) awaitCancellation()
          RecordingStyleBinding()
        },
        capture = { _, _ ->
          image
        },
        cancel = {
          cleanupStarted.complete(Unit)
          releaseCleanup.await()
        },
      )
    val runtime = runtimeWith(adapter)
    val snapshotter = runtime.createSnapshotter(BaseStyle.Empty)
    val activeRequest = MapSnapshotRequest(1, 1)
    val nextRequest = MapSnapshotRequest(2, 2)
    val active = async { snapshotter.capture(activeRequest) }
    val next = async { snapshotter.capture(nextRequest) }
    assertSame(activeRequest, started.receive())

    active.cancelAndJoin()
    cleanupStarted.await()

    assertFalse(started.tryReceive().isSuccess)
    releaseCleanup.complete(Unit)
    assertSame(nextRequest, started.receive())
    assertSame(image, next.await())
    close(snapshotter, runtime)
  }

  @Test
  fun caller_timeout_abandons_the_result_and_delays_the_next_capture() = runTest {
    val started = Channel<MapSnapshotRequest>(Channel.UNLIMITED)
    val cleanupStarted = CompletableDeferred<Unit>()
    val releaseCleanup = CompletableDeferred<Unit>()
    val nextImage = FakeImageBitmap(2, 2)
    val adapter =
      FakeSnapshotterAdapter(
        capture = { request, _ ->
          started.send(request)
          if (request.width == 1) awaitCancellation()
          nextImage
        },
        cancel = {
          cleanupStarted.complete(Unit)
          releaseCleanup.await()
        },
      )
    val runtime = runtimeWith(adapter)
    val snapshotter = runtime.createSnapshotter(BaseStyle.Empty)
    val timedOut =
      async(Dispatchers.Default) {
        assertFailsWith<TimeoutCancellationException> {
          withTimeout(50.milliseconds) { snapshotter.capture(MapSnapshotRequest(1, 1)) }
        }
      }
    assertEquals(1, started.receive().width)
    val next = async { snapshotter.capture(MapSnapshotRequest(2, 2)) }
    cleanupStarted.await()

    assertFalse(started.tryReceive().isSuccess)
    releaseCleanup.complete(Unit)
    timedOut.await()
    assertEquals(2, started.receive().width)
    assertSame(nextImage, next.await())
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

    snapshotter.style.baseStyle = replacementStyle
    releaseFailure.complete(Unit)
    assertTrue(staleResult.await().isFailure)
    assertEquals(StyleLoadState.Pending, snapshotter.style.loadState)

    snapshotter.capture(MapSnapshotRequest(1, 1))
    assertEquals(listOf<BaseStyle>(initialStyle, replacementStyle), requestedStyles)
    assertEquals(StyleLoadState.Ready, snapshotter.style.loadState)
    close(snapshotter, runtime)
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
    val reported = assertFailsWith<MapSnapshotterCleanupException> { snapshotter.awaitClosed() }
    assertEquals(2, reported.failures.size)
    assertSame(cleanupFailure, reported.failures[0])
    assertSame(closeFailure, reported.failures[1])
    runtime.close()
    runtime.awaitClosed()
  }

  @Test
  fun closure_refuses_new_work_clears_the_queue_and_waits_for_active_cleanup() = runTest {
    supervisorScope {
      val started = CompletableDeferred<Unit>()
      val releaseCleanup = CompletableDeferred<Unit>()
      val adapter =
        FakeSnapshotterAdapter(
          capture = { _, _ ->
            started.complete(Unit)
            awaitCancellation()
          },
          cancel = {
            releaseCleanup.await()
          },
        )
      val runtime = runtimeWith(adapter)
      val snapshotter = runtime.createSnapshotter(BaseStyle.Empty)
      val active = async { snapshotter.capture(MapSnapshotRequest(1, 1)) }
      val queued = async { snapshotter.capture(MapSnapshotRequest(2, 2)) }
      started.await()

      snapshotter.close()
      val closure = async { snapshotter.awaitClosed() }

      assertFailsWith<MapSnapshotterClosedException> { queued.await() }
      assertFailsWith<MapSnapshotterClosedException> {
        snapshotter.capture(MapSnapshotRequest(3, 3))
      }
      assertFalse(closure.isCompleted)
      releaseCleanup.complete(Unit)
      assertFailsWith<MapSnapshotterClosedException> { active.await() }
      closure.await()
      runtime.close()
      runtime.awaitClosed()
    }
  }

  private fun runtimeWith(adapter: SnapshotterAdapter): MapRuntime =
    mapRuntimeForTest(
      snapshotterAdapterFactory = SnapshotterAdapterFactory { adapter },
      styleEvaluator = StyleCompositionEvaluator { _, _, _, _, _ -> DesiredStyleRevision.Empty },
    )

  private suspend fun close(snapshotter: MapSnapshotter, runtime: MapRuntime) {
    snapshotter.close()
    snapshotter.awaitClosed()
    runtime.close()
    runtime.awaitClosed()
  }

  private class FakeSnapshotterAdapter(
    private val prepare: suspend (BaseStyle, MapSnapshotRequest) -> StyleBinding = { _, _ ->
      RecordingStyleBinding()
    },
    private val capture: suspend (MapSnapshotRequest, DesiredStyleRevision) -> ImageBitmap =
      { request, _ ->
        FakeImageBitmap(request.width, request.height)
      },
    private val cancel: suspend () -> Unit = {},
    private val close: suspend () -> Unit = {},
  ) : SnapshotterAdapter {
    override suspend fun prepare(baseStyle: BaseStyle, request: MapSnapshotRequest): StyleBinding =
      prepare.invoke(baseStyle, request)

    override suspend fun capture(
      request: MapSnapshotRequest,
      revision: DesiredStyleRevision,
    ): ImageBitmap = capture.invoke(request, revision)

    override suspend fun cancelActiveCapture() = cancel.invoke()

    override suspend fun close() = close.invoke()
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
}
