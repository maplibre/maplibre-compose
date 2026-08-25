package org.maplibre.compose.gljs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlinx.browser.document
import org.maplibre.compose.map.MapExtent

class EmscriptenGlTest {

  @Test
  fun a_disconnected_leftover_context_does_not_hide_the_current_canvas() = gpuTest { gpu ->
    withExtraEmscriptenContext {
      assertSame(gpu.canvas, EmscriptenGl.skikoCanvas(), "the current Skia canvas")
    }
  }

  @Test
  fun a_connected_canvas_is_preferred_over_a_disconnected_leftover() = gpuTest {
    withExtraEmscriptenContext(connected = true) { extra ->
      assertSame(extra, EmscriptenGl.skikoCanvas(), "the canvas still in the document")
    }
  }

  @Test
  fun the_compositor_keeps_drawing_when_the_registry_holds_a_leftover() = gpuTest { gpu ->
    val gl = gpu.gl.asDynamic()
    ComposeGlJsCompositor(logger = null).use { compositor ->
      val first =
        assertIs<GlJsFrameTarget.Composited>(
            compositor.acquire(MapExtent.fromPhysical(FULL, FULL, 1.0))
          )
          .target
      withExtraEmscriptenContext {
        val again =
          assertIs<GlJsFrameTarget.Composited>(
              compositor.acquire(MapExtent.fromPhysical(FULL, FULL, 1.0))
            )
            .target
        assertEquals(first.generation, again.generation, "the same target should be reused")
        val resized =
          assertIs<GlJsFrameTarget.Composited>(
              compositor.acquire(MapExtent.fromPhysical(SMALL, SMALL, 1.0))
            )
            .target
        assertEquals(SMALL, resized.widthPx)
        assertEquals(FULL, gl.drawingBufferWidth.unsafeCast<Int>(), "the leftover is not current")
      }
    }
  }

  @Test
  fun the_newest_connected_canvas_wins_over_a_connected_leftover() = gpuTest { gpu ->
    document.body.asDynamic().appendChild(gpu.canvas)
    try {
      withExtraEmscriptenContext(connected = true) { extra ->
        assertSame(extra, EmscriptenGl.skikoCanvas(), "the last connected canvas")
      }
    } finally {
      gpu.canvas.remove()
    }
  }

  @Test
  fun a_replaced_context_on_the_same_canvas_mints_a_new_target() = gpuTest { gpu ->
    val holder = gpu.canvas.asDynamic()
    val original = holder.GLctxObject
    ComposeGlJsCompositor(logger = null).use { compositor ->
      val first =
        assertIs<GlJsFrameTarget.Composited>(
            compositor.acquire(MapExtent.fromPhysical(FULL, FULL, 1.0))
          )
          .target
      val replacement: dynamic = js("({})")
      replacement.GLctx = js("Object").create(original.GLctx)
      holder.GLctxObject = replacement
      try {
        val again =
          assertIs<GlJsFrameTarget.Composited>(
              compositor.acquire(MapExtent.fromPhysical(FULL, FULL, 1.0))
            )
            .target
        assertNotEquals(
          first.generation,
          again.generation,
          "a new context should mint a new target at the same size",
        )
      } finally {
        holder.GLctxObject = original
      }
    }
  }
}

private const val FULL = GPU_CANVAS_SIZE
private const val SMALL = FULL / 2
