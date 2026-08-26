package org.maplibre.compose.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.maplibre.compose.gljs.runBrowserMapTest
import org.maplibre.compose.gljs.setBrowserMapContent
import org.maplibre.compose.gljs.waitUntilMap
import org.maplibre.compose.style.BaseStyle

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
    assertNull(stateB.attachedAdapter, "the unshown state has no session")

    useSecond = true
    waitUntilMap("the swap to detach the old state and attach the new one") {
      stateA.attachedAdapter == null && stateB.attachedAdapter != null
    }

    assertTrue(firstSession.isClosed, "the old state's live map is disposed by the swap")
    waitUntilMap("the new state's map to load") { loads >= 2 }
    assertNotNull(stateB.attachedAdapter, "the swapped-in state keeps its session")
    assertNull(stateA.attachedAdapter, "the swapped-away state stays detached")
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
    val engine = assertIs<GlJsMapEngine>(state.engine)

    state.close()

    assertTrue(session.isClosed, "closing the state closes the live session")
    assertNull(engine.session, "the engine forgets the closed session")
    assertNull(state.attachedAdapter, "the closed state is detached")
  }
}
