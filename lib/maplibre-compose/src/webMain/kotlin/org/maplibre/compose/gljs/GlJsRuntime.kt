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
