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

/** Closing a [MapState] closes and refuses browser sessions. */
@OptIn(ExperimentalTestApi::class)
class BrowserMapStateCloseTest {

  private val style =
    BaseStyle.Json(
      """{"version":8,"sources":{},
         "layers":[{"id":"bg","type":"background","paint":{"background-color":"#123456"}}]}"""
    )

  /**
   * One composed map walks the close contract: the close ends the live session, a session
   * registered by hand afterwards is refused, and a session the still-composed view registers is
   * refused and renders nothing.
   */
  @Test
  fun closing_the_state_closes_the_live_session_and_refuses_every_later_one() = runBrowserMapTest {
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
    assertNull(engine.session, "the engine forgets the closed session")
    assertFalse(state.isAttached, "the closed state is detached")

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
