package org.maplibre.compose.gljs

import kotlin.js.JsAny
import web.gl.WebGL2RenderingContext

/** The worker built with the embedded engine, shared by every map in this module. */
internal val DefaultWorkerUrl: String by lazy { getDefaultWorkerUrl() }

/**
 * A same-origin worker URL for [workerUrl]. A cross-origin URL becomes a blob that `import`s the
 * absolute form of that URL, which is what MapLibre GL JS 6 does for a CDN worker. Same-origin URLs
 * are returned as they are.
 *
 * MapLibre wraps worker URLs with `new URL(url, import.meta.url)`. Webpack rewrites that into a
 * module lookup, which fails for an `https` URL with "Cannot find module". This path avoids
 * `import.meta.url` so custom cross-origin workers work when the library is bundled.
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
      """,
    )
    .unsafeCast<String>()

/**
 * Adapts MapLibre GL JS internals for rendering into Compose. Compatibility checks detect changes
 * to the internals used here.
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
