package org.maplibre.compose.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import co.touchlab.kermit.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.maplibre.compose.gljs.runBrowserMapTest
import org.maplibre.compose.gljs.setBrowserMapContent
import org.maplibre.compose.gljs.waitUntilMap
import org.maplibre.compose.gljs.yieldToBrowser
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.RecordingLogger

/** Swapping the state argument of a composed [MaplibreMap] moves the session between states. */
@OptIn(ExperimentalTestApi::class)
class BrowserMapStateSwapTest {

  private val style =
    BaseStyle.Json(
      """{"version":8,"sources":{},
         "layers":[{"id":"bg","type":"background","paint":{"background-color":"#123456"}}]}"""
    )

  @Test
  fun swapping_the_state_disposes_the_old_map_and_attaches_the_new_state() = runBrowserMapTest {
    var useSecond by mutableStateOf(false)
    var loads = 0
    lateinit var stateA: MapState
    lateinit var stateB: MapState

    setBrowserMapContent {
      stateA = rememberMapState(baseStyle = style)
      stateB = rememberMapState(baseStyle = style)
      MaplibreMap(
        state = if (useSecond) stateB else stateA,
        modifier = Modifier.fillMaxSize(),
        logger = null,
        onMapLoadFinished = { loads++ },
      )
    }

    waitUntilMap("the first map to load") { loads >= 1 }
    val firstSession = assertIs<GlJsMapSession>(stateA.attachedAdapter)
    assertFalse(stateB.isAttached, "the unshown state has no session")

    useSecond = true
    waitUntilMap("the swap to detach the old state and attach the new one") {
      !stateA.isAttached && stateB.isAttached
    }

    assertTrue(firstSession.isClosed, "the old state's live map is disposed by the swap")
    waitUntilMap("the new state's map to load") { loads >= 2 }
    assertTrue(stateB.isAttached, "the swapped-in state keeps its session")
    assertFalse(stateA.isAttached, "the swapped-away state stays detached")
  }

  @Test
  fun closing_the_state_closes_the_composed_session() = runBrowserMapTest {
    val state = MapState()
    state.baseStyle = style
    var loads = 0

    setBrowserMapContent {
      MaplibreMap(
        state = state,
        modifier = Modifier.fillMaxSize(),
        logger = null,
        onMapLoadFinished = { loads++ },
      )
    }

    waitUntilMap("the map to load") { loads >= 1 }
    val session = assertIs<GlJsMapSession>(state.attachedAdapter)
    val engine = state.engine

    state.close()

    assertTrue(session.isClosed, "closing the state closes the live session")
    assertNull(engine.session, "the engine forgets the closed session")
    assertFalse(state.isAttached, "the closed state is detached")
  }

  @Test
  fun a_session_composed_after_close_is_refused_and_renders_nothing() = runBrowserMapTest {
    val state = MapState()
    state.baseStyle = style
    var loads = 0
    var frames = 0
    var scale by mutableStateOf(1f)
    val log = RecordingLogger("closed-state-test", Severity.Warn)

    setBrowserMapContent {
      CompositionLocalProvider(LocalDensity provides Density(scale)) {
        MaplibreMap(
          state = state,
          modifier = Modifier.fillMaxSize(),
          logger = log.logger,
          onMapLoadFinished = { loads++ },
          onFrame = { frames++ },
        )
      }
    }

    waitUntilMap("the map to load") { loads >= 1 }
    val engine = state.engine
    val firstSession = assertIs<GlJsMapSession>(state.attachedAdapter)

    state.close()
    assertTrue(firstSession.isClosed, "closing the state closes the composed session")

    val refused =
      GlJsMapSession(
        callbacks = state.callbacks,
        logger = log.logger,
        layoutDirection = LayoutDirection.Ltr,
      )
    engine.registerSession(refused)
    assertTrue(refused.isClosed, "a session registered after close is closed at once")
    assertNull(engine.session, "a session registered after close is not retained")

    // A changed remember key makes the still-composed view register a fresh session.
    scale = 2f
    waitUntilMap("the closed engine to refuse the fresh session") {
      log.messages.any { "closed MapState" in it }
    }
    val framesAtRefusal = frames
    repeat(5) {
      yieldToBrowser()
      waitForIdle()
    }

    assertNull(engine.session, "the closed engine retains no session")
    assertFalse(state.isAttached, "nothing attaches to the closed state")
    assertEquals(1, loads, "the refused session never loads a style")
    assertEquals(framesAtRefusal, frames, "the refused session renders nothing")
  }
}
