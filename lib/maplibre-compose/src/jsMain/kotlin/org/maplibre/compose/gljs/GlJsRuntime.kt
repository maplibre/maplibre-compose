package org.maplibre.compose.gljs

import web.gl.WebGL2RenderingContext

/**
 * The places MapLibre GL JS is bent at runtime to be driven as a headless renderer. Each is pinned
 * to internals of one MapLibre version and fails loudly when a version bump moves them.
 */
internal object GlJsRuntime {

  private var workerUrlConfigured = false

  /**
   * MapLibre GL JS 6 loads vector tiles in `maplibre-gl-worker.mjs`. Webpack copies that file, and
   * its sibling `maplibre-gl-shared.mjs`, next to the page; this points MapLibre at them. Repeat
   * calls are ignored.
   */
  fun pointAtBundledWorker() {
    if (workerUrlConfigured) return
    // Relative to the page: webpack and Karma both serve the file at this name next to it.
    setWorkerUrl("maplibre-gl-worker.mjs")
    workerUrlConfigured = true
  }

  /** Runs [build] with every WebGL context request on the page answered by [gl]. */
  fun <T> lendingContext(gl: WebGL2RenderingContext, build: () -> T): T {
    val prototype = js("HTMLCanvasElement").prototype
    val original = prototype.getContext
    var lent = 0
    prototype.getContext = { type: String, options: dynamic ->
      // `this` is the canvas being asked; skiko's own canvas must keep answering for itself.
      val receiver = js("this")
      if (receiver !== gl.canvas && (type == "webgl2" || type == "webgl")) {
        lent++
        gl
      } else {
        original.call(receiver, type, options)
      }
    }
    try {
      return build()
    } finally {
      prototype.getContext = original
      check(lent == 1) {
        "MapLibre asked for $lent WebGL contexts while its map was being created, not one. It no " +
          "longer takes its context from its canvas the way this platform assumes."
      }
    }
  }

  /**
   * `bindFramebuffer.set(null)` is the one place in MapLibre's renderer that means "the screen".
   * [target] is read per call, because a resize replaces the framebuffer while the map renders.
   */
  fun redirectDefaultFramebuffer(context: Context, target: () -> Any?) {
    val binding = context.asDynamic().bindFramebuffer
    check(jsTypeOf(binding.set) == "function") {
      "MapLibre's Context.bindFramebuffer no longer has a set method to redirect"
    }
    binding.set = { requested: dynamic ->
      val self = js("this")
      val next = if (requested == null || requested == undefined) target() else requested
      if (next !== self.current || self.dirty == true) {
        self.gl.bindFramebuffer(self.gl.FRAMEBUFFER, next)
        self.current = next
        self.dirty = false
      }
    }
  }

  /** `Map.remove` ends by losing its context, which is fatal here: the context is Compose's. */
  fun removingWithoutLosingContext(context: WebGL2RenderingContext, remove: () -> Unit) {
    val gl = context.asDynamic()
    val original = gl.getExtension
    gl.getExtension = { name: String ->
      if (name == "WEBGL_lose_context") null else original.call(gl, name)
    }
    try {
      remove()
    } finally {
      gl.getExtension = original
    }
  }

  /** `triggerRepaint` is MapLibre's only caller of `browser.frame`. */
  fun interceptRepaintRequests(map: MaplibreMap, onRequest: () -> Unit) {
    val dynamicMap = map.asDynamic()
    check(jsTypeOf(dynamicMap.triggerRepaint) == "function") {
      "MapLibre's Map no longer has a triggerRepaint method to intercept"
    }
    dynamicMap.triggerRepaint = { onRequest() }
  }
}
