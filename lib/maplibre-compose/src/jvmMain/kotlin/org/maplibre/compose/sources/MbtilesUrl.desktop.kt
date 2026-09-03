package org.maplibre.compose.sources

import java.io.File
import java.net.JarURLConnection
import java.net.URI
import java.net.URISyntaxException
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
 * Resolves [uri] to a file on disk, copying a `jar:` entry into [directory].
 *
 * @throws IllegalArgumentException when [uri] is not a URI, or is neither a `file:` URI nor an
 *   entry in a jar on disk.
 */
internal suspend fun desktopMbtilesPath(uri: String, directory: File): String =
  withContext(Dispatchers.IO) {
    val parsed =
      try {
        URI(uri)
      } catch (error: URISyntaxException) {
        throw IllegalArgumentException("'$uri' is not a URI", error)
      }
    if (parsed.scheme.equals("file", ignoreCase = true)) {
      return@withContext Paths.get(parsed).toAbsolutePath().toString()
    }
    val jar = jarFileOf(parsed)
    val copy =
      copyPackagedFile(
        uri = uri,
        directory = directory,
        // A jar entry changes only with its jar, so the jar identifies the copy.
        stamp = "${jar.length()}-${jar.lastModified()}",
        open = { parsed.toURL().openConnection().apply { useCaches = false }.getInputStream() },
      )
    copy.absolutePath
  }

/** The jar on disk that holds the entry at [uri], which is a `jar:file:` URI. */
private fun jarFileOf(uri: URI): File {
  val rejection = "mbtilesUrl reads a file: URI or a jar: entry in a jar on disk, not '$uri'"
  require(uri.scheme.equals("jar", ignoreCase = true)) { rejection }
  val connection =
    uri.toURL().openConnection() as? JarURLConnection ?: throw IllegalArgumentException(rejection)
  val jar = runCatching {
    File(connection.jarFileURL.toURI())
  }
    .getOrElse { throw IllegalArgumentException(rejection, it) }
  require(jar.isFile) { "'$uri' names a jar that does not exist: $jar" }
  return jar
}
