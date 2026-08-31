package org.maplibre.compose.map

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.maplibre.compose.gljs.mapWaitDiagnostics
import org.maplibre.compose.gljs.pumpPublishedDetachedFrame
import org.maplibre.compose.gljs.runBrowserMapTest
import org.maplibre.compose.gljs.setBrowserMapContent
import org.maplibre.compose.gljs.waitUntilMap
import org.maplibre.compose.style.BaseStyle

@OptIn(ExperimentalTestApi::class)
class BrowserMapStateTest {

  @Test
  fun waitUntilMap_reports_diagnostics_when_a_style_never_arrives(): Promise<*> =
    runBrowserMapTest {
      val runtime = createMapRuntime(MapRuntimeOptions())
      val state = runtime.createMapState(initialBaseStyle = BaseStyle.Empty)
      setBrowserMapContent {}
      waitForIdle()

      val error =
        assertFailsWith<AssertionError> {
          waitUntilMap(
            "a style that never arrives",
            timeout = 1.seconds,
            diagnostics = { mapWaitDiagnostics(state, extra = "events=[]") },
          ) {
            false
          }
        }
      val message = error.message.orEmpty()
      assertContains(message, "frames waiting for a style that never arrives")
      assertContains(message, "presentation=null")
      assertContains(message, "style=${StyleLoadState.Pending}")
      assertContains(message, "events=[]")

      runtime.close()
      runtime.awaitClosed()
    }

  @Test
  fun remembered_runtime_is_shared_and_survives_state_disposal(): Promise<*> = runBrowserMapTest {
    val includeState = mutableStateOf(true)
    lateinit var firstRuntime: MapRuntime
    lateinit var secondRuntime: MapRuntime
    lateinit var state: MapState
    setBrowserMapContent {
      firstRuntime = rememberMapRuntime()
      secondRuntime = rememberMapRuntime()
      if (includeState.value) {
        val remembered = rememberMapState(firstRuntime, initialBaseStyle = BaseStyle.Empty)
        SideEffect { state = remembered }
      }
    }
    waitForIdle()

    assertSame(firstRuntime, secondRuntime)
    assertTrue(!state.isClosed)

    runOnIdle { includeState.value = false }
    waitUntilMap(
      "the remembered state to close",
      diagnostics = { mapWaitDiagnostics(state) },
      pump = { pumpPublishedDetachedFrame(state) },
    ) {
      state.isClosed
    }
    assertTrue(state.isClosed)

    assertFalse(firstRuntime.isClosed)
  }

  @Test
  fun map_state_renders_a_base_style_and_publishes_one_presentation(): Promise<*> =
    runBrowserMapTest {
      val runtime = createMapRuntime(MapRuntimeOptions())
      val state = runtime.createMapState(initialBaseStyle = BaseStyle.Empty)
      val includeRival = mutableStateOf(false)

      setBrowserMapContent {
        MaplibreMap(state)
        if (includeRival.value) MaplibreMap(state)
      }
      waitUntilMap(
        "the logical map presentation to load",
        diagnostics = { mapWaitDiagnostics(state) },
        pump = { pumpPublishedDetachedFrame(state) },
      ) {
        state.presentation != null && state.style.loadState == StyleLoadState.Ready
      }

      assertNotNull(state.presentation)
      val presentation = state.presentation!!
      assertTrue(presentation.isValid)
      val createdSessions = GlJsMapSession.createdCount

      runOnIdle { includeRival.value = true }
      assertFailsWith<IllegalStateException> { waitForIdle() }
      assertEquals(createdSessions, GlJsMapSession.createdCount)
      assertSame(presentation, state.presentation)
      assertTrue(presentation.isValid)

      setContent {}
      waitUntilMap(
        "the presentation to detach",
        diagnostics = { mapWaitDiagnostics(state) },
        pump = { pumpPublishedDetachedFrame(state) },
      ) {
        state.presentation == null
      }
      assertNull(state.presentation)
      assertFalse(state.isClosed)

      runtime.close()
      runtime.awaitClosed()
    }
}
