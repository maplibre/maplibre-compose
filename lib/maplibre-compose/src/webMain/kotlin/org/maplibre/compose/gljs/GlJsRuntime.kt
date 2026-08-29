package org.maplibre.compose.gljs

import kotlin.js.JsAny
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
 * A same-origin worker URL for [workerUrl]. A cross-origin URL becomes a blob that `import`s the
 * absolute form of that URL, which is what MapLibre GL JS 6 does for a CDN worker. Same-origin URLs
 * are returned as they are.
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
        var resolvedWorkerUrl;
        try {
          resolvedWorkerUrl = new URL(workerUrl, loc.href);
          if (resolvedWorkerUrl.origin === loc.origin) return workerUrl;
        } catch (e) {
          return workerUrl;
        }
        return URL.createObjectURL(
          new Blob(
            ["import " + JSON.stringify(resolvedWorkerUrl.href)],
            {type: "text/javascript"}
          )
        );
      })()
      """
  )

private fun newCanvasContextHook(): JsAny =
  js(
    """({
    prototype: HTMLCanvasElement.prototype,
    original: HTMLCanvasElement.prototype.getContext,
    lent: 0
  })"""
  )

private fun installCanvasContextHook(hook: JsAny, gl: WebGL2RenderingContext): Unit =
  js(
    """{
      var original = hook.original;
      hook.prototype.getContext = function(type, options) {
        if (this !== gl.canvas && (type === 'webgl2' || type === 'webgl')) {
          hook.lent = hook.lent + 1;
          return gl;
        }
        return original.call(this, type, options);
      };
    }"""
  )

private fun restoreCanvasContextHook(hook: JsAny): Int =
  js(
    """{
      hook.prototype.getContext = hook.original;
      return hook.lent;
    }"""
  )

private fun defineDrawingBufferSize(gl: WebGL2RenderingContext, width: Int, height: Int): JsAny =
  js(
    """{
      var objects = Object;
      var previousWidth = objects.getOwnPropertyDescriptor(gl, "drawingBufferWidth");
      var previousHeight = objects.getOwnPropertyDescriptor(gl, "drawingBufferHeight");
      objects.defineProperty(gl, "drawingBufferWidth", {configurable: true, value: width});
      objects.defineProperty(gl, "drawingBufferHeight", {configurable: true, value: height});
      return {
        objects: objects,
        gl: gl,
        previousWidth: previousWidth,
        previousHeight: previousHeight
      };
    }"""
  )

private fun restoreDrawingBufferSize(state: JsAny): Unit =
  js(
    """{
      var objects = state.objects;
      var gl = state.gl;
      if (state.previousWidth == null) {
        Reflect.deleteProperty(gl, "drawingBufferWidth");
      } else {
        objects.defineProperty(gl, "drawingBufferWidth", state.previousWidth);
      }
      if (state.previousHeight == null) {
        Reflect.deleteProperty(gl, "drawingBufferHeight");
      } else {
        objects.defineProperty(gl, "drawingBufferHeight", state.previousHeight);
      }
    }"""
  )

private fun hasBindFramebufferSet(context: Context): Boolean =
  js("!!context.bindFramebuffer && typeof context.bindFramebuffer.set === 'function'")

private fun installFramebufferRedirect(context: Context, target: () -> JsAny?): Unit =
  js(
    """{
      var binding = context.bindFramebuffer;
      binding.set = function(requested) {
        var next = (requested == null) ? target() : requested;
        if (next !== this.current || this.dirty === true) {
          this.gl.bindFramebuffer(this.gl.FRAMEBUFFER, next);
          this.current = next;
          this.dirty = false;
        }
      };
    }"""
  )

private fun installLoseContextBlock(gl: WebGL2RenderingContext): JsAny =
  js(
    """{
      var original = gl.getExtension.bind(gl);
      gl.getExtension = function(name) {
        if (name === 'WEBGL_lose_context') return null;
        return original(name);
      };
      return original;
    }"""
  )

private fun restoreGetExtension(gl: WebGL2RenderingContext, original: JsAny): Unit =
  js("{ gl.getExtension = original; }")

private fun hasTriggerRepaint(map: MaplibreMap): Boolean =
  js("typeof map.triggerRepaint === 'function'")

private fun setTriggerRepaint(map: MaplibreMap, onRequest: () -> Unit): Unit =
  js("{ map.triggerRepaint = onRequest; }")

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
    val hook = newCanvasContextHook()
    installCanvasContextHook(hook, gl)
    try {
      return build()
    } finally {
      val lent = restoreCanvasContextHook(hook)
      check(lent == 1) {
        "MapLibre asked for $lent WebGL contexts while its map was being created, not one. It no " +
          "longer takes its context from its canvas the way this platform assumes."
      }
    }
  }

  /**
   * Runs [draw] while [gl] reports [width] and [height] as its drawing buffer size.
   *
   * MapLibre normally renders into its context's canvas, so some fullscreen passes read the canvas
   * drawing buffer instead of the current framebuffer. The Compose compositor redirects that
   * framebuffer to a map-sized texture inside a larger shared canvas.
   */
  fun <T> withDrawingBufferSize(
    gl: WebGL2RenderingContext,
    width: Int,
    height: Int,
    draw: () -> T,
  ): T {
    val state = defineDrawingBufferSize(gl, width, height)
    try {
      return draw()
    } finally {
      restoreDrawingBufferSize(state)
    }
  }

  /**
   * `bindFramebuffer.set(null)` is the one place in MapLibre's renderer that means "the screen".
   * [target] is read per call, because a resize replaces the framebuffer while the map renders.
   */
  fun redirectDefaultFramebuffer(context: Context, target: () -> JsAny?) {
    check(hasBindFramebufferSet(context)) {
      "MapLibre's Context.bindFramebuffer no longer has a set method to redirect"
    }
    installFramebufferRedirect(context, target)
  }

  /** `Map.remove` ends by losing its context, which is fatal here: the context is Compose's. */
  fun removingWithoutLosingContext(context: WebGL2RenderingContext, remove: () -> Unit) {
    val original = installLoseContextBlock(context)
    try {
      remove()
    } finally {
      restoreGetExtension(context, original)
    }
  }

  /** `triggerRepaint` is MapLibre's only caller of `browser.frame`. */
  fun interceptRepaintRequests(map: MaplibreMap, onRequest: () -> Unit) {
    check(hasTriggerRepaint(map)) {
      "MapLibre's Map no longer has a triggerRepaint method to intercept"
    }
    setTriggerRepaint(map, onRequest)
  }
}
