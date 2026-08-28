package org.maplibre.compose.map

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/** MapLibre GL JS has no still-image API, and [MapState.captureStillImage] reports that. */
class BrowserSnapshotTest {

  @Test
  fun snapshot_reports_the_gl_js_limitation() = runTest {
    val state = MapState()
    try {
      assertIs<MapLoadState.Idle>(state.loadState)
      assertFailsWith<UnsupportedOperationException> {
        state.captureStillImage(width = 10.dp, height = 10.dp)
      }
      assertIs<MapLoadState.Idle>(
        state.loadState,
        "an unsupported snapshot must not select a style or take the render lease",
      )
    } finally {
      state.close()
    }
  }
}
