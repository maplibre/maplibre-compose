package org.maplibre.compose.gljs

import web.gl.WebGL2RenderingContext

/**
 * The default MapLibre GL JS 6 worker URL: the build of [getVersion] on the jsDelivr CDN. The
 * worker imports `maplibre-gl-shared.mjs` as a sibling, which the CDN serves alongside it, so no
 * bundler setup is needed for a map to render. Pass a different URL to [GlJsRuntime.pointAtWorker]
 * only to self-host the worker or pin a version other than the bundled one.
 *
 * The version is read from the bundled library rather than the version catalog so it can never
 * drift from the maplibre-gl the page actually links.
 */
internal val DEFAULT_WORKER_URL: String by lazy {
  "https://cdn.jsdelivr.net/npm/maplibre-gl@${getVersion()}/dist/maplibre-gl-worker.mjs"
}

/**
 * A same-origin worker URL for [workerUrl]. Cross-origin `http(s)` URLs become a blob that
 * `import`s them, which is what MapLibre GL JS 6 does for a CDN worker. Same-origin URLs are
 * returned as they are.
 *
 * MapLibre's own laundering uses `new URL(url, import.meta.url)`. Webpack rewrites that into a
 * module lookup, which fails for an `https` URL with "Cannot find module". This path avoids
 * `import.meta.url` so the CDN default works when the library is bundled.
 */
internal fun sameOriginWorkerUrl(workerUrl: String): String =
  js(
      """
      (function() {
        var loc = globalThis.location;
        if (!loc) return workerUrl;
        try {
          if (new URL(workerUrl, loc.href).origin === loc.origin) return workerUrl;
        } catch (e) {
          return workerUrl;
        }
        return URL.createObjectURL(
          new Blob(["import " + JSON.stringify(workerUrl)], {type: "text/javascript"})
        );
      })()
      """
    )
    .unsafeCast<String>()

/**
 * The places MapLibre GL JS is bent at runtime to be driven as a headless renderer. Each is pinned
 * to internals of one MapLibre version and fails loudly when a version bump moves them.
 */
internal object GlJsRuntime {

  private var workerUrlConfigured = false

  /** Points MapLibre GL JS 6 at [workerUrl]. The first call wins; later calls are ignored. */
  fun pointAtWorker(workerUrl: String) {
    if (workerUrlConfigured) return
    setWorkerUrl(sameOriginWorkerUrl(workerUrl))
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
