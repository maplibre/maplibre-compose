package org.maplibre.compose.sources

import platform.Foundation.NSURL

/** Bundle resources are files on disk on iOS, so a `file:` URI needs no copy. */
internal actual suspend fun localMbtilesPath(uri: String): String {
  val url = requireNotNull(NSURL.URLWithString(uri)) { "'$uri' is not a URL" }
  require(url.scheme.equals("file", ignoreCase = true) && url.host.isNullOrEmpty()) {
    "MapLibre Native reads MBTiles from a local file: URI, not '$uri'"
  }
  return requireNotNull(url.path) { "'$uri' has no path" }
}
