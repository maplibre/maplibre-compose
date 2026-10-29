package org.maplibre.compose.gljs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeViewport
import kotlin.js.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.browser.document
import kotlinx.browser.window
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.testing.runMapTest
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

class BrowserComposeTargetTest {
  @Test
  fun the_first_frame_and_resize_use_the_measured_framebuffer_size() = runMapTest {
    val container =
      (document.createElement("div") as HTMLElement).apply {
        style.width = "128px"
        style.height = "96px"
      }
    document.body!!.appendChild(container)
    val frames = mutableListOf<Pair<MapExtent, Pair<Int, Int>>>()
    val renderer =
      object : GlJsMapRenderer {
        override fun onSurfaceAvailable(surface: GlJsSurfaceSession) = Unit

        override fun onSurfaceLost(surface: GlJsSurfaceSession) = Unit

        override fun close() = Unit

        override fun render(target: GlJsFrameTarget, extent: MapExtent): Boolean {
          val framebuffer = (target as GlJsFrameTarget.Composited).target
          frames += extent to (framebuffer.widthPx to framebuffer.heightPx)
          return true
        }
      }
    suspend fun awaitFrame(width: Int) {
      val start = Date.now()
      while (frames.lastOrNull()?.first?.width != width) {
        check(Date.now() - start < 10_000) { "No Compose map frame at width $width: $frames" }
        yieldToBrowser()
      }
    }
    try {
      ComposeViewport(container) {
        GlJsMapSurface(renderer, Modifier.fillMaxSize(), logger = null, presentFrames = true)
      }
      awaitFrame(128)
      container.style.width = "96px"
      window.dispatchEvent(Event("resize"))
      awaitFrame(96)
      assertTrue(frames.isNotEmpty())
      frames.forEach { (extent, size) ->
        assertEquals(extent.physicalWidth to extent.physicalHeight, size)
      }
    } finally {
      container.remove()
      yieldToBrowser()
    }
  }
}
