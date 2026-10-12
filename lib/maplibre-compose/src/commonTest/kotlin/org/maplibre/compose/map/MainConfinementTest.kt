package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

  private class RejectingStyleAdapter : PresentationTestAdapter() {
    override fun setBaseStyle(style: BaseStyle) {
      error("style rejected")
    }
  }
}
