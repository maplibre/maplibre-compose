package org.maplibre.compose.sources

import java.io.File
import java.net.JarURLConnection
import java.net.URI
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.compose.desktop.desktopUserCacheDirectory

/** Copies are stored under `maplibre-compose/mbtiles` in the per-user cache directory. */
internal actual suspend fun localMbtilesPath(uri: String): String =
  desktopMbtilesPath(
    uri,
    desktopUserCacheDirectory().resolve("maplibre-compose").resolve("mbtiles").toFile(),
  )

/**
 * Resolves [uri] to a file on disk, copying a `jar:` entry or any other non-file URL that the JDK
 * can open into [directory].
 *
 * @throws IllegalArgumentException when [uri] is not a URI or names a protocol the JDK cannot open.
 */
internal suspend fun desktopMbtilesPath(uri: String, directory: File): String =
  withContext(Dispatchers.IO) {
    val parsed =
      try {
        URI(uri)
      } catch (error: java.net.URISyntaxException) {
        throw IllegalArgumentException("'$uri' is not a URI", error)
      }
    if (parsed.scheme.equals("file", ignoreCase = true)) {
      return@withContext Paths.get(parsed).toAbsolutePath().toString()
    }
    val url =
      try {
        parsed.toURL()
      } catch (error: Exception) {
        throw IllegalArgumentException("'$uri' names a protocol this platform cannot open", error)
      }
    val copy =
      copyPackagedFile(
        uri = uri,
        directory = directory,
        stamp = packagedStamp(url),
        open = { url.openConnection().apply { useCaches = false }.getInputStream() },
      )
    copy.absolutePath
  }

/**
 * Identifies the package version behind [url]. A jar entry changes only with its jar, so the jar's
 * size and modification time serve without opening the archive. Any other URL is opened for its
 * headers, and the stream is closed at once.
 */
private fun packagedStamp(url: java.net.URL): String {
  val connection = url.openConnection().apply { useCaches = false }
  val jar =
    (connection as? JarURLConnection)?.jarFileURL?.toURI()?.let {
      runCatching { File(it) }.getOrNull()
    }
  if (jar != null) return "${jar.length()}-${jar.lastModified()}"
  connection.getInputStream().close()
  return "${connection.contentLengthLong}-${connection.lastModified}"
}
