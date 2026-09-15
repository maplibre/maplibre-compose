package org.maplibre.compose.gljs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.compose.map.MapExtent

@OptIn(ExperimentalTestApi::class)
class BrowserMapSurfaceTest {
  @Test
  fun frames_prepare_before_overlay_placement_and_idle_draws_do_not_render() = runBrowserMapTest {
    val revision = mutableIntStateOf(0)
    val size = mutableStateOf(128.dp)
    val color = mutableStateOf(Color.Red)
    var placedRevision = -1
    var renderedExtent = MapExtent.Empty
    var closed = false
    lateinit var host: GlJsSurfaceSession
    val renderer =
      object : GlJsMapRenderer {
        override fun onSurfaceAvailable(surface: GlJsSurfaceSession) {
          host = surface
        }

        override fun onSurfaceLost() = Unit

        override fun render(target: GlJsFrameTarget, extent: MapExtent): Boolean {
          renderedExtent = extent
          revision.intValue++
          return true
        }

        override fun close() {
          closed = true
        }
      }
    setBrowserMapContent {
      Box {
        CompositionLocalProvider(
          LocalGlJsCompositor provides
            {
              object : GlJsCompositor {
                override fun acquire(extent: MapExtent): GlJsFrameTarget =
                  if (extent.width == 96) GlJsFrameTarget.UnsupportedSize
                  else GlJsFrameTarget.Detached

                override fun close() = Unit
              }
            }
        ) {
          GlJsMapSurface(renderer, Modifier.size(size.value), logger = null, presentFrames = true)
        }
        Box(
          Modifier.size(16.dp)
            .layout { measurable, constraints ->
              val child = measurable.measure(constraints)
              layout(child.width, child.height) {
                placedRevision = revision.intValue
                child.place(0, 0)
              }
            }
            .drawBehind { drawRect(color.value) }
        )
      }
    }
    waitForIdle()
    assertTrue(revision.intValue > 0)
    assertEquals(revision.intValue, placedRevision)
    val initial = revision.intValue
    runOnIdle { color.value = Color.Blue }
    waitForIdle()
    assertEquals(initial, revision.intValue, "an unrelated draw must reuse the prepared map")
    runOnIdle { repeat(20) { host.requestFrame() } }
    waitForIdle()
    assertEquals(initial + 1, revision.intValue, "requests before one frame should coalesce")
    assertEquals(revision.intValue, placedRevision)
    val beforeUnsupportedSize = revision.intValue
    runOnIdle { size.value = 96.dp }
    waitForIdle()
    assertEquals(
      beforeUnsupportedSize,
      revision.intValue,
      "unsupported sizes must not render or retry",
    )
    assertEquals(false, closed, "unsupported sizes must not close the renderer")
    runOnIdle { size.value = 64.dp }
    waitForIdle()
    assertEquals(64, renderedExtent.width)
    assertEquals(64, renderedExtent.height)
    assertEquals(revision.intValue, placedRevision)
  }
}
