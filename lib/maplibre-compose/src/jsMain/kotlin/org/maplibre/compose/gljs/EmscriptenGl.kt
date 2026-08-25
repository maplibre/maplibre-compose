package org.maplibre.compose.gljs

import web.gl.WebGL2RenderingContext
import web.html.HTMLCanvasElement

internal const val GL_TEXTURE_2D: Int = 0x0DE1
internal const val GL_RGBA8: Int = 0x8058

/** The Emscripten context that owns the Compose draw pass currently in progress. */
internal class EmscriptenGlContext(
  val handle: Int,
  val webGlContext: WebGL2RenderingContext,
)

/**
 * Emscripten's GL bookkeeping, which Skiko's loader publishes as a global. The current entry
 * identifies the renderer driving this frame even though Compose puts its canvas inside a shadow
 * root.
 */
internal object EmscriptenGl {
  private val registry: dynamic
    get() = js("globalThis").GL

  private val isAvailable: Boolean
    get() {
      val gl = registry
      return gl != null && gl != undefined
    }

  /** The handle that Skiko made current, including handles from retired renderers. */
  fun currentHandle(): Int? {
    if (!isAvailable) return null
    val entry = registry.currentContext
    if (entry == null || entry == undefined) return null
    return entry.handle as? Int
  }

  /**
   * The context for the Compose draw pass currently in progress.
   *
   * Compose can leave one animation frame queued on a renderer that a resize replaced. The canvas
   * points at the newest Emscripten entry, so a different current entry belongs to a retired frame.
   */
  fun currentContext(): EmscriptenGlContext? {
    if (!isAvailable) return null
    val entry = registry.currentContext
    if (entry == null || entry == undefined) return null
    val handle = entry.handle as? Int ?: return null
    val context = entry.GLctx
    if (context == null || context == undefined || context.isContextLost() == true) return null
    val canvas = context.canvas
    if (canvas == null || canvas == undefined) return null
    val typedCanvas = canvas.unsafeCast<HTMLCanvasElement>()
    if (typedCanvas.asDynamic().GLctxObject !== entry) return null
    return EmscriptenGlContext(
      handle = handle,
      webGlContext = context.unsafeCast<WebGL2RenderingContext>(),
    )
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
