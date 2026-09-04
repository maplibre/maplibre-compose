package org.maplibre.compose.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import org.maplibre.compose.resource.encodeResourceUrl

/**
 * Returns the `mbtiles:` URL of the MBTiles file at [uri], for the `tiles` list of a [VectorSource]
 * or a [RasterSource].
 *
 * MapLibre Native opens an MBTiles file through SQLite, so the file must be on the file system. A
 * `file:` URI that names a file on the file system converts directly. A URI that names a packaged
 * resource, such as an Android asset or a jar entry in a packaged desktop application, is copied
 * into the application's cache directory. The copy is named by a digest of its content, so every
 * call reads the resource to identify it, and a later call reuses the copy while the content is
 * unchanged.
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
 * The state is null while the first call copies a packaged resource out of the application package,
 * and returns to null when [uri] changes. Declare the source once the value is available.
 */
@Composable
public fun rememberMbtilesUrl(uri: String): State<String?> =
  produceState<String?>(initialValue = null, uri) {
    value = null
    value = mbtilesUrl(uri)
  }

/**
 * The absolute file system path of the MBTiles file at [uri], copied out of the package if needed.
 */
internal expect suspend fun localMbtilesPath(uri: String): String

/**
 * Percent-encodes [path] after the `mbtiles://` prefix. MapLibre Native percent-decodes the rest of
 * the URL to a path, so `/` stays as is and every other reserved character is encoded.
 */
internal fun mbtilesUrlForPath(path: String): String =
  "mbtiles://" + path.split('/').joinToString("/", transform = ::encodeResourceUrl)
