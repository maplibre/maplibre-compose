package org.maplibre.compose.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState

/**
 * Returns the `mbtiles:` URL of the MBTiles file at [uri], for the `tiles` list of a [VectorSource]
 * or a [RasterSource].
 *
 * MapLibre Native opens an MBTiles file through SQLite, so the file must be on the file system. A
 * `file:` URI that names a file on the file system converts directly. A URI that names a packaged
 * resource, such as an Android asset or a jar entry in a packaged desktop application, is copied
 * into the application's cache directory on the first call. A later call reuses the copy until the
 * application package changes.
 *
 * `Res.getUri` from Compose Resources returns one of these URIs on every platform except the
 * browser.
 *
 * @throws UnsupportedOperationException on the browser platform. MapLibre GL JS does not read
 *   MBTiles files.
 * @throws IllegalArgumentException when [uri] is not a URI that this platform reads.
 */
public suspend fun mbtilesUrl(uri: String): String = mbtilesUrlForPath(localMbtilesPath(uri))

/**
 * Returns the `mbtiles:` URL of the MBTiles file at [uri] once [mbtilesUrl] has produced it, and
 * null before that.
 *
 * The state stays null while the first call copies a packaged resource out of the application
 * package. Declare the source once the value is available.
 */
@Composable
public fun rememberMbtilesUrl(uri: String): State<String?> =
  produceState<String?>(initialValue = null, uri) { value = mbtilesUrl(uri) }

/**
 * The absolute file system path of the MBTiles file at [uri], copied out of the package if needed.
 */
internal expect suspend fun localMbtilesPath(uri: String): String

/**
 * Percent-encodes [path] after the `mbtiles://` prefix. MapLibre Native percent-decodes the rest of
 * the URL to a path, so `/` stays as is and every other reserved character is encoded.
 */
internal fun mbtilesUrlForPath(path: String): String {
  val out = StringBuilder("mbtiles://")
  for (byte in path.encodeToByteArray()) {
    val value = byte.toInt() and 0xFF
    val char = value.toChar()
    if (char == '/' || char.isUnreservedUrlChar()) out.append(char)
    else {
      out.append('%')
      out.append(value.toString(16).padStart(2, '0').uppercase())
    }
  }
  return out.toString()
}

private fun Char.isUnreservedUrlChar(): Boolean =
  this in 'a'..'z' ||
    this in 'A'..'Z' ||
    this in '0'..'9' ||
    this == '-' ||
    this == '_' ||
    this == '.' ||
    this == '~'
