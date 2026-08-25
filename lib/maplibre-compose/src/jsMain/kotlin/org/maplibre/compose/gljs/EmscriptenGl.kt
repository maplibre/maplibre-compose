package org.maplibre.compose.gljs

import web.gl.WebGL2RenderingContext
import web.html.HTMLCanvasElement

internal const val GL_TEXTURE_2D: Int = 0x0DE1
internal const val GL_RGBA8: Int = 0x8058

/**
 * Emscripten's GL bookkeeping, which skiko's loader publishes as a global. It is the only list of
 * the contexts on the page: Compose puts its canvas inside a shadow root.
 */
internal object EmscriptenGl {
  private val registry: dynamic
    get() = js("globalThis").GL

  private val isAvailable: Boolean
    get() {
      val gl = registry
      return gl != null && gl != undefined
    }

  /**
   * The canvas of the WebGL context Skia is drawing with, or null before Compose has built its
   * renderer.
   *
   * Emscripten keeps every context it created, so a resize can leave a previous canvas in the list.
   * A connected leftover is ignored in favor of the last live canvas, which is the one Skia created
   * most recently. When every canvas is detached, this returns the current context.
   */
  fun skikoCanvas(): HTMLCanvasElement? {
    val live = liveCanvases()
    if (live.isEmpty()) return null
    // A resize can leave the previous canvas connected and still current for a frame. The last
    // live canvas is the one Skia created most recently.
    if (live.any { it.isConnected }) return live.last()
    val current = currentCanvas()
    return live.firstOrNull { it === current } ?: live.last()
  }

  /** Live canvases, preferring those still in the document. */
  private fun liveCanvases(): List<HTMLCanvasElement> {
    if (!isAvailable) return emptyList()
    val contexts = registry.contexts ?: return emptyList()
    val length = contexts.length as? Int ?: return emptyList()
    val all = ArrayList<HTMLCanvasElement>()
    for (index in 0 until length) {
      val canvas = canvasOf(contexts[index]) ?: continue
      all.add(canvas)
    }
    val connected = all.filter { it.isConnected }
    return connected.ifEmpty { all }
  }

  private fun currentCanvas(): HTMLCanvasElement? =
    if (isAvailable) canvasOf(registry.currentContext) else null

  private fun canvasOf(entry: dynamic): HTMLCanvasElement? {
    if (entry == null || entry == undefined) return null
    val gl = entry.GLctx
    if (gl == null || gl == undefined || gl.isContextLost() == true) return null
    val canvas = gl.canvas
    if (canvas == null || canvas == undefined) return null
    return canvas.unsafeCast<HTMLCanvasElement>()
  }

  fun contextOf(canvas: HTMLCanvasElement): WebGL2RenderingContext? {
    val context = canvas.asDynamic().GLctxObject?.GLctx
    if (context == null || context == undefined || context.isContextLost() == true) return null
    return context.unsafeCast<WebGL2RenderingContext>()
  }

  /** A texture created from JavaScript has no name, and Skia's wasm build addresses it by one. */
  fun registerTexture(texture: Any): Int {
    check(isAvailable) { "emscripten's GL registry is not on the page" }
    val name = registry.getNewId(registry.textures) as Int
    texture.asDynamic().name = name
    registry.textures[name] = texture
    return name
  }
}
