package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleIdentity
import org.maplibre.compose.style.StyleLoadTracker
import org.maplibre.compose.style.StyleRequestId

class MapSessionEventsTest {
  private val tracker = StyleLoadTracker()
  private val recorder = RecordingCallbacks()

  /** Posts queue here, as they do when an engine thread posts to main. */
  private val main = ArrayDeque<() -> Unit>()
  private val events =
    MapSessionEvents(PresentationTestAdapter(), TrackerStamps(tracker), main::addLast) { recorder }

  private fun runMain() {
    while (true) main.removeFirstOrNull()?.invoke() ?: return
  }

  private fun loadStyle(): StyleIdentity =
    StyleIdentity.create().also { check(tracker.loaded(tracker.request(), it)) }

  @Test
  fun a_queued_style_report_is_dropped_once_its_style_is_replaced() {
    val first = loadStyle()

    events.styleReady(first)
    val second = loadStyle()
    runMain()
    assertEquals(emptyList(), recorder.delivered)

    events.styleReady(second)
    runMain()
    assertEquals(listOf("ready"), recorder.delivered)
  }

  @Test
  fun superseded_style_request_reports_are_dropped() {
    val superseded = tracker.request()
    val current = tracker.request()

    events.styleFailed(superseded, "superseded")
    events.styleRequestEvent(superseded, MapEvent.StyleLoadFailed("superseded"))
    events.styleFailed(current, "current")
    events.styleRequestEvent(current, MapEvent.StyleLoadFailed("current"))
    runMain()

    assertEquals(
      listOf("failed(current)", "event(StyleLoadFailed(reason=current))"),
      recorder.delivered,
    )
  }

  @Test
  fun a_dropped_missing_image_request_is_still_answered() {
    val replaced = loadStyle()
    val answer = events.resolveMissingImage(replaced, "marker")
    loadStyle()
    runMain()

    assertTrue(answer.isCompleted)
    assertEquals(emptyList(), recorder.delivered)
  }

  @Test
  fun a_missing_image_request_is_answered_once_the_owner_resolves_it() {
    val style = loadStyle()
    val resolution = CompletableDeferred<Unit>()
    recorder.resolution = resolution

    val answer = events.resolveMissingImage(style, "marker")
    runMain()
    assertEquals(listOf("resolve(marker)"), recorder.delivered)
    assertFalse(answer.isCompleted)

    resolution.complete(Unit)
    assertTrue(answer.isCompleted)
  }

  @Test
  fun a_presentation_report_queued_before_a_reattachment_is_dropped() = runTest {
    val lifecycle =
      mapRuntimeForTest(physicalScope = backgroundScope)
        .createMapState(BaseStyle.Demo)
        .lifecycle
        .createRetainedEngineLifecycle(NoOpEngineSteps, PresentationTestAdapter())
    // The native session's presentation stamp: the engine it created and the attached lease.
    val stamps =
      object : SessionStamps by TrackerStamps(tracker) {
        override fun isCurrentPresentation(engine: EngineMapIdentity, lease: RenderLease) =
          !lifecycle.isClosing && engine == lifecycle.engine && lease == lifecycle.lease
      }
    val events = MapSessionEvents(PresentationTestAdapter(), stamps, main::addLast) { recorder }
    lifecycle.attach()
    val engine = checkNotNull(lifecycle.engine)

    events.viewportChanged(engine, checkNotNull(lifecycle.lease))
    lifecycle.detach()
    lifecycle.attach()
    runMain()
    assertEquals(emptyList(), recorder.delivered)

    events.viewportChanged(engine, checkNotNull(lifecycle.lease))
    runMain()
    assertEquals(listOf("viewport"), recorder.delivered)
  }

  private object NoOpEngineSteps : RetainedEngineSteps {
    override suspend fun createEngine(identity: EngineMapIdentity) = Unit

    override suspend fun attach(identity: EngineMapIdentity, lease: RenderLease) = Unit

    override suspend fun detach(identity: EngineMapIdentity, lease: RenderLease) = Unit

    override suspend fun destroyEngine(identity: EngineMapIdentity) = Unit

    override suspend fun closeResources() = Unit
  }

  /** Accepts every engine and presentation, and the style stamps that [tracker] holds current. */
  private class TrackerStamps(private val tracker: StyleLoadTracker) : SessionStamps {
    override fun isCurrentEngine(engine: EngineMapIdentity) = true

    override fun isCurrentPresentation(engine: EngineMapIdentity, lease: RenderLease) = true

    override fun isCurrentStyleRequest(request: StyleRequestId) = request === tracker.requestId

    override fun isCurrentStyle(style: StyleIdentity) = tracker.isCurrent(style)
  }

  private class RecordingCallbacks : MapAdapter.Callbacks {
    val delivered = mutableListOf<String>()
    var resolution: Deferred<Unit>? = null

    override fun onStyleChanged(map: MapAdapter, style: StyleBinding?) {
      delivered += "styleChanged"
    }

    override fun onStyleReady(map: MapAdapter) {
      delivered += "ready"
    }

    override fun onStyleFailed(map: MapAdapter, reason: String?) {
      delivered += "failed($reason)"
    }

    override fun onStyleSourcesChanged(map: MapAdapter, sourceId: String?) {
      delivered += "sources($sourceId)"
    }

    override fun onEvent(map: MapAdapter, event: MapEvent) {
      delivered += "event($event)"
    }

    override fun resolveMissingImage(map: MapAdapter, imageId: String): Deferred<Unit>? {
      delivered += "resolve($imageId)"
      return resolution
    }

    override fun onGestureActive(map: MapAdapter, active: Boolean) = Unit

    override fun onViewportChanged(map: MapAdapter) {
      delivered += "viewport"
    }
  }
}
