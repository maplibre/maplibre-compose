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
   * Null before Compose has built its renderer.
   *
   * @throws IllegalStateException if the page holds more than one live WebGL context. The Skia
   *   context this pairs with is whichever was created last, and WebGL shares no resources between
   *   contexts, so guessing between them would draw a map from a texture another context owns.
   */
  fun skikoCanvas(): HTMLCanvasElement? {
    if (!isAvailable) return null
    val contexts = registry.contexts ?: return null
    val length = contexts.length as? Int ?: return null
    var found: HTMLCanvasElement? = null
    for (index in 0 until length) {
      val entry = contexts[index]
      if (entry == null || entry == undefined) continue
      val canvas = entry.GLctx?.canvas
      if (canvas == null || canvas == undefined) continue
      check(found == null) {
        "MapLibre Compose supports one Compose renderer per page, and this page has more than one " +
          "live WebGL context."
      }
      found = canvas.unsafeCast<HTMLCanvasElement>()
    }
    return found
  }

  fun contextOf(canvas: HTMLCanvasElement): WebGL2RenderingContext? {
    val context = canvas.asDynamic().GLctxObject?.GLctx
    if (context == null || context == undefined) return null
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
