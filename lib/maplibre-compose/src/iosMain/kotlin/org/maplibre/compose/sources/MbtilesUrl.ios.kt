package org.maplibre.compose.sources

import platform.Foundation.NSURL

/** Bundle resources are files on disk on iOS, so a `file:` URI needs no copy. */
internal actual suspend fun localMbtilesPath(uri: String): String {
  val url = requireNotNull(NSURL.URLWithString(uri)) { "'$uri' is not a URL" }
  require(url.scheme == "file") { "MapLibre Native reads MBTiles from a file: URI, not '$uri'" }
  return requireNotNull(url.path) { "'$uri' has no path" }
}
