package org.maplibre.compose.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.maplibre.compose.gljs.runBrowserMapTest
import org.maplibre.compose.gljs.setBrowserMapContent
import org.maplibre.compose.gljs.waitUntilMap
import org.maplibre.compose.style.BaseStyle

/** Recomposing a [MaplibreMap] with a different state replaces the session and its browser map. */
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
}
