package org.maplibre.compose.sources

import java.io.File
import java.net.JarURLConnection
import java.net.URI
import java.nio.file.Paths
import org.maplibre.compose.desktop.desktopCachePath
import org.maplibre.compose.desktop.inferredApplicationId

/** Copies land beside the application's cache database, in the per-user cache directory. */
internal actual suspend fun localMbtilesPath(uri: String): String {
  val parsed = URI(uri)
  if (parsed.scheme.equals("file", ignoreCase = true)) {
    return Paths.get(parsed).toAbsolutePath().toString()
  }
  val directory = desktopCachePath(inferredApplicationId()).resolveSibling("mbtiles").toFile()
  return desktopMbtilesPath(uri, directory)
}

/**
 * Resolves [uri] to a file on disk, copying a `jar:` entry or any other non-file URL that the JDK
 * can open into [directory].
 */
internal suspend fun desktopMbtilesPath(uri: String, directory: File): String {
  val parsed = URI(uri)
  if (parsed.scheme.equals("file", ignoreCase = true)) {
    return Paths.get(parsed).toAbsolutePath().toString()
  }
  val url = parsed.toURL()
  val connection = url.openConnection().apply { useCaches = false }
  // The entry's own size and time, plus the enclosing jar's when there is one, identify the copy.
  val jar =
    (connection as? JarURLConnection)?.jarFileURL?.toURI()?.let {
      runCatching { File(it) }.getOrNull()
    }
  val stamp =
    listOfNotNull(
        connection.contentLengthLong,
        connection.lastModified,
        jar?.length(),
        jar?.lastModified(),
      )
      .joinToString("-")
  val copy =
    copyPackagedFile(
      destination = File(directory, packagedCopyName(uri)),
      stamp = stamp,
      open = { url.openConnection().apply { useCaches = false }.getInputStream() },
    )
  return copy.absolutePath
}
