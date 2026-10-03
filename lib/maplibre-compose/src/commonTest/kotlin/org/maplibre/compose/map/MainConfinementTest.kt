package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.style.BaseStyle

/** Runs map state through a main dispatcher that queues, as production does off the main thread. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainConfinementTest {

  @Test
  fun a_runtime_rejects_an_unconfined_main_dispatcher() {
    val error =
      assertFailsWith<IllegalArgumentException> {
        mapRuntimeForTest(mainDispatcher = Dispatchers.Unconfined)
      }
    assertTrue(error.message.orEmpty().contains("Dispatchers.Unconfined"))
  }

  @Test
  fun a_configuration_failure_at_publication_marks_the_style_failed() = runTest {
    val main = StandardTestDispatcher(testScheduler)
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope, mainDispatcher = main)
    testScheduler.runCurrent() // The dispatcher pins the main thread.
    val state = runtime.createMapState(BaseStyle.Demo)
    val adapter = RejectingStyleAdapter()

    // Publication runs on main, so its configuration failure is handled before it returns.
    state.publishPresentation(state.reservePresentation(), adapter)

    assertEquals(StyleLoadState.Failed("style rejected"), state.style.loadState)
    state.close()
    testScheduler.runCurrent()
    state.awaitClosed()
    runtime.close()
  }

  @Test
  fun closure_reports_closed_at_once_and_await_closed_waits_for_the_posted_commit() = runTest {
    val main = StandardTestDispatcher(testScheduler)
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope, mainDispatcher = main)
    testScheduler.runCurrent() // The dispatcher pins the main thread.
    val state = runtime.createMapState(BaseStyle.Demo)
    state.publishPresentation(state.reservePresentation(), PresentationTestAdapter())
    testScheduler.runCurrent()
    val attachment = state.currentMapAttachment
    assertTrue(attachment != null)

    state.close()
    assertTrue(state.isClosed)
    // The map state commits on the main dispatcher; nothing has run there yet.
    assertTrue(state.currentMapAttachment === attachment)
    val closed = async { state.awaitClosed() }
    testScheduler.runCurrent()
    assertNull(state.currentMapAttachment)
    assertFalse(attachment.isValid)
    closed.await()
    runtime.close()
  }

  @Test
  fun a_close_request_rejects_new_platform_callbacks_before_the_closure_commits() = runTest {
    val main = StandardTestDispatcher(testScheduler)
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope, mainDispatcher = main)
    testScheduler.runCurrent() // The dispatcher pins the main thread.
    val state = runtime.createMapState(BaseStyle.Demo)
    val adapter = PresentationTestAdapter()
    state.publishPresentation(state.reservePresentation(), adapter)
    assertTrue(state.lifecycle.acceptEnginePlatformAccess(adapter) {})

    state.close()

    // Nothing has run on main yet: the closure is queued, but new work is already refused.
    assertTrue(state.isClosed)
    assertFalse(state.lifecycle.acceptEnginePlatformAccess(adapter) {})
    assertFalse(state.lifecycle.acceptsAdapter(adapter))
    testScheduler.runCurrent()
    state.awaitClosed()
    runtime.close()
  }

  @Test
  fun a_replaced_retained_adapter_closes_only_after_the_callback_running_on_it_ends() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Demo)
    val retained = ClosableRetainedAdapter(compatibilityKey = "a")
    assertSame(retained, state.lifecycle.retainAdapterForPlatformAccess { retained })

    // A platform callback is running on the retained adapter when main publishes an
    // incompatible replacement, which retires the retained adapter.
    val replacement = ClosableRetainedAdapter(compatibilityKey = "b")
    assertTrue(
      state.lifecycle.acceptEnginePlatformAccess(retained) {
        state.publishPresentation(state.reservePresentation(), replacement)
        assertFalse(retained.closeCalled)
      }
    )

    assertTrue(retained.closeCalled)
    assertFalse(replacement.closeCalled)
    state.close()
    state.awaitClosed()
    runtime.close()
  }

  private class ClosableRetainedAdapter(private val compatibilityKey: Any) :
    PresentationTestAdapter() {
    var closeCalled = false

    override val retainsEngineBetweenPresentations = true
    override val presentationCompatibilityKey: Any = compatibilityKey

    override suspend fun detachPresentation() = Unit

    override fun close() {
      closeCalled = true
    }
  }

  private class RejectingStyleAdapter : PresentationTestAdapter() {
    override fun setBaseStyle(style: BaseStyle) {
      error("style rejected")
    }
  }
}
