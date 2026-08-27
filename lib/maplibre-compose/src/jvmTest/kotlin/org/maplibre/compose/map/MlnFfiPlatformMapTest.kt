@file:OptIn(DelicateMapApi::class)

package org.maplibre.compose.map

import androidx.compose.ui.unit.dp
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.maplibre.compose.mlnffi.FfiTestCache
import org.maplibre.compose.mlnffi.MlnFfiApplication
import org.maplibre.compose.style.BaseStyle

/** [withPlatformMap] against the map a [MapState] owns outside any composition. */
class MlnFfiPlatformMapTest {

  private val cache = FfiTestCache()

  private var state: MapState? = null

  @AfterTest
  fun cleanUp() {
    state?.close()
    MlnFfiApplication.resetForTest()
    cache.close()
  }

  private fun bareState(): MapState {
    cache.configure()
    return MapState().also { state = it }
  }

  @Test
  fun a_state_that_never_created_a_map_throws() {
    val state = bareState()
    val failure =
      assertFailsWith<IllegalStateException> { runBlocking { state.withPlatformMap {} } }
    assertTrue(
      "attach or snapshot" in assertNotNull(failure.message),
      "the message must name the attach-or-snapshot creation moment, got: ${failure.message}",
    )
  }

  @Test
  fun a_closed_state_throws() {
    val state = bareState()
    state.close()
    assertFailsWith<IllegalStateException> { runBlocking { state.withPlatformMap {} } }
  }

  @Test
  fun a_snapshotted_state_reads_the_live_map_while_detached() {
    val state = bareState()
    state.baseStyle = BACKGROUND_STYLE
    runBlocking { state.snapshot(width = 20.dp, height = 20.dp, timeout = 60.seconds) }

    val layerIds = runBlocking { state.withPlatformMap { it.styleLayerIds() } }

    assertTrue("bg" in layerIds, "the loaded style's layer must be readable, got: $layerIds")
  }

  @Test
  fun a_state_closed_after_a_snapshot_throws() {
    val state = bareState()
    state.baseStyle = BACKGROUND_STYLE
    runBlocking { state.snapshot(width = 20.dp, height = 20.dp, timeout = 60.seconds) }
    state.close()
    assertFailsWith<IllegalStateException> {
      runBlocking { state.withPlatformMap { it.styleLayerIds() } }
    }
  }

  private companion object {
    val BACKGROUND_STYLE =
      BaseStyle.Json(
        """{"version":8,"sources":{},
           "layers":[{"id":"bg","type":"background","paint":{"background-color":"#ff0000"}}]}"""
      )
  }
}
