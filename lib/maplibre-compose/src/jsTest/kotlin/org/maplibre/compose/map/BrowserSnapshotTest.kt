package org.maplibre.compose.map

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

/** MapLibre GL JS has no still-image API, and [MapState.captureStillImage] reports that. */
class BrowserSnapshotTest {

  @Test
  fun snapshot_reports_the_gl_js_limitation() = runTest {
    val state = MapState()
    try {
      assertFailsWith<UnsupportedOperationException> {
        state.captureStillImage(width = 10.dp, height = 10.dp)
      }
    } finally {
      state.close()
    }
  }
}
