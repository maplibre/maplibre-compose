package org.maplibre.compose.gljs

import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.promise
import org.maplibre.compose.map.MapExtent

class EmscriptenGlTest {

  @Test
  fun a_disconnected_leftover_context_does_not_hide_the_current_canvas() = gpuTest { gpu ->
    withExtraEmscriptenContext(connected = false) {
      assertSame(
        gpu.canvas,
        EmscriptenGl.skikoCanvas(),
        "a leftover registry entry should not replace the current Skia canvas",
      )
    }
  }

  @Test
  fun a_connected_canvas_is_preferred_over_a_disconnected_leftover() = gpuTest { gpu ->
    withExtraEmscriptenContext(connected = true) { extra ->
      val found = EmscriptenGl.skikoCanvas()
      assertSame(
        extra,
        found,
        "a resize leftover that left the document should lose to the new canvas",
      )
      assertTrue(extra.isConnected, "the extra canvas should still be in the document")
      assertTrue(!gpu.canvas.isConnected, "the suite canvas is never attached")
    }
  }

  @Test
  fun the_compositor_keeps_drawing_when_the_registry_holds_a_leftover() = gpuTest { gpu ->
    val gl = gpu.gl.asDynamic()
    ComposeGlJsCompositor(logger = null).use { compositor ->
      val first =
        assertIsComposited(compositor.acquire(MapExtent.fromPhysical(FULL, FULL, 1.0))).target
      withExtraEmscriptenContext(connected = false) {
        val again =
          assertIsComposited(compositor.acquire(MapExtent.fromPhysical(FULL, FULL, 1.0))).target
        assertEquals(first.generation, again.generation, "the same target should be reused")
        val resized =
          assertIsComposited(compositor.acquire(MapExtent.fromPhysical(SMALL, SMALL, 1.0))).target
        assertEquals(SMALL, resized.widthPx)
        assertEquals(
          FULL,
          gl.drawingBufferWidth.unsafeCast<Int>(),
          "the leftover context should not have become current",
        )
      }
    }
  }
}

private const val FULL = GPU_CANVAS_SIZE
private const val SMALL = FULL / 2

private fun gpuTest(block: suspend (BrowserGpu) -> Unit): Promise<*> =
  MainScope().promise { block(browserGpu()) }

private fun assertIsComposited(target: GlJsFrameTarget): GlJsFrameTarget.Composited {
  assertTrue(target is GlJsFrameTarget.Composited, "expected a composited target, got $target")
  return target
}
