package org.maplibre.compose.gljs

import web.gl.WebGL2RenderingContext
import web.html.HTMLCanvasElement

internal class EmscriptenGlContext(val webGlContext: WebGL2RenderingContext)

/** Test-only access to the Emscripten GL registry used by Skiko's wasm loader. */
internal object EmscriptenGl {
  private val registry: dynamic
    get() = js("globalThis").GL

  fun currentContext(): EmscriptenGlContext? {
    val entry = registry?.currentContext
    if (entry == null || entry == undefined) return null
    val context = entry.GLctx
    if (context == null || context == undefined || context.isContextLost() == true) return null
    val canvas = context.canvas
    if (canvas == null || canvas == undefined) return null
    if (canvas.unsafeCast<HTMLCanvasElement>().asDynamic().GLctxObject !== entry) return null
    return EmscriptenGlContext(context.unsafeCast<WebGL2RenderingContext>())
  }

  fun registerTexture(texture: Any): Int {
    val name = registry.getNewId(registry.textures) as Int
    texture.asDynamic().name = name
    registry.textures[name] = texture
    return name
  }
}
