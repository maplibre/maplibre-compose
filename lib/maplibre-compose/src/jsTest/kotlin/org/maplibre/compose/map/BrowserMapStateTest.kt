package org.maplibre.compose.map

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.maplibre.compose.gljs.runBrowserMapTest
import org.maplibre.compose.gljs.setBrowserMapContent
import org.maplibre.compose.gljs.waitUntilMap
import org.maplibre.compose.style.BaseStyle

@OptIn(ExperimentalTestApi::class)
class BrowserMapStateTest {

  @Test
  fun remembered_runtime_is_shared_and_survives_state_disposal(): Promise<*> = runBrowserMapTest {
    val includeState = mutableStateOf(true)
    lateinit var firstRuntime: MapRuntime
    lateinit var secondRuntime: MapRuntime
    lateinit var state: MapState
    setBrowserMapContent {
      firstRuntime = rememberDefaultMapRuntime()
      secondRuntime = rememberDefaultMapRuntime()
      if (includeState.value) {
        val remembered = rememberMapState(firstRuntime, baseStyle = BaseStyle.Empty)
        SideEffect { state = remembered }
      }
    }
    waitForIdle()

    assertSame(firstRuntime, secondRuntime)
    assertTrue(!state.isClosed)

    runOnIdle { includeState.value = false }
    waitUntilMap("the remembered state to close") { state.isClosed }
    assertTrue(state.isClosed)

    assertFalse(firstRuntime.isClosed)
  }

  @Test
  fun map_state_renders_a_base_style_and_publishes_one_presentation(): Promise<*> =
    runBrowserMapTest {
      val runtime = createMapRuntime(MapRuntimeOptions())
      val state = runtime.createMapState(baseStyle = BaseStyle.Empty)
      val includeRival = mutableStateOf(false)

      setBrowserMapContent {
        MaplibreMap(state)
        if (includeRival.value) MaplibreMap(state)
      }
      waitUntilMap("the logical map presentation to load") {
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
      waitUntilMap("the presentation to detach") { state.presentation == null }
      assertNull(state.presentation)
      assertFalse(state.isClosed)

      runtime.close()
      runtime.awaitClosed()
    }
}
