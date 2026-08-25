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
   * Emscripten keeps every context it created. A resize can leave a previous canvas in that list
   * after Compose has built a new one. This pairs with the current context when one is bound, and
   * otherwise with the last live canvas, which is the one Skia created most recently.
   *
   * A page with more than one Compose renderer is unsupported, because those contexts do not share
   * GPU resources.
   */
  fun skikoCanvas(): HTMLCanvasElement? {
    val live = liveCanvases()
    if (live.isEmpty()) return null
    val current = currentCanvas()
    if (current != null && live.any { it === current }) return current
    return live.last()
  }

  /**
   * Canvases whose WebGL context is still live. A disconnected leftover is kept only when no
   * connected canvas remains, so a test canvas that was never attached still resolves, and a resize
   * that left a detached previous canvas does not.
   */
  private fun liveCanvases(): List<HTMLCanvasElement> {
    if (!isAvailable) return emptyList()
    val contexts = registry.contexts ?: return emptyList()
    val length = contexts.length as? Int ?: return emptyList()
    val connected = ArrayList<HTMLCanvasElement>()
    val disconnected = ArrayList<HTMLCanvasElement>()
    for (index in 0 until length) {
      val canvas = canvasOf(contexts[index]) ?: continue
      if (canvas.isConnected) connected.add(canvas) else disconnected.add(canvas)
    }
    return if (connected.isNotEmpty()) connected else disconnected
  }

  private fun currentCanvas(): HTMLCanvasElement? {
    if (!isAvailable) return null
    return canvasOf(registry.currentContext)
  }

  private fun canvasOf(entry: dynamic): HTMLCanvasElement? {
    if (entry == null || entry == undefined) return null
    val gl = entry.GLctx
    if (gl == null || gl == undefined) return null
    if (gl.isContextLost() == true) return null
    val canvas = gl.canvas
    if (canvas == null || canvas == undefined) return null
    return canvas.unsafeCast<HTMLCanvasElement>()
  }

  fun contextOf(canvas: HTMLCanvasElement): WebGL2RenderingContext? {
    val context = canvas.asDynamic().GLctxObject?.GLctx
    if (context == null || context == undefined) return null
    if (context.isContextLost() == true) return null
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
