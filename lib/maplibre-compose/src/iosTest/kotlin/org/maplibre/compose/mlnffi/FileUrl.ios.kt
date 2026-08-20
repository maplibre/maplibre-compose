package org.maplibre.compose.mlnffi

import kotlinx.io.files.Path
import platform.Foundation.NSURL

internal actual fun fileUrlOf(path: Path): String =
  checkNotNull(NSURL.fileURLWithPath(path.toString()).absoluteString) {
    "NSURL could not write a file URL for $path"
  }

internal actual fun pathOfFileUrl(url: String): Path {
  val parsed = checkNotNull(NSURL.URLWithString(url)) { "NSURL could not parse $url" }
  return Path(checkNotNull(parsed.path) { "The URL $url names no file" })
}
