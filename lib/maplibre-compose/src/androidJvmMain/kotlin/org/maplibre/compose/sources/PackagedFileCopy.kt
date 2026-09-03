package org.maplibre.compose.sources

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One lock per destination, so two callers that want the same file copy it once. */
private val copyLocks = ConcurrentHashMap<String, Mutex>()

/**
 * Copies the packaged resource at [uri] into [directory] unless a copy of the same [uri] with the
 * same [stamp] is already there, and returns the copy.
 *
 * [stamp] identifies the package version that the resource came from. The copy is written to a
 * temporary file and moved into place, so a reader sees either the old copy or the new one.
 */
internal suspend fun copyPackagedFile(
  uri: String,
  directory: File,
  stamp: String,
  open: () -> InputStream,
): File {
  val destination = File(directory, packagedCopyName(uri))
  // The stamp records the source URI as well as the package version, so a copy is never reused for
  // a different resource.
  val expected = "$uri\n$stamp"
  val lock = copyLocks.getOrPut(destination.absolutePath) { Mutex() }
  lock.withLock {
    withContext(Dispatchers.IO) {
      val stampFile = File(destination.path + ".stamp")
      val current = destination.isFile && stampFile.isFile && stampFile.readText() == expected
      if (!current) {
        destination.parentFile?.mkdirs()
        stampFile.delete()
        val temporary = File.createTempFile(destination.name, ".part", destination.parentFile)
        try {
          open().use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
          // A rename onto an existing file is platform-specific, so remove the old copy first.
          destination.delete()
          check(temporary.renameTo(destination)) { "Could not move $temporary to $destination" }
        } finally {
          temporary.delete()
        }
        stampFile.writeText(expected)
      }
    }
  }
  return destination
}

/** The file name for the copy of [uri]: a digest of the whole URI, then its last path segment. */
private fun packagedCopyName(uri: String): String {
  val name = uri.substringAfterLast('/').substringBefore('?').ifEmpty { "tiles.mbtiles" }
  val digest = MessageDigest.getInstance("SHA-256").digest(uri.encodeToByteArray())
  return digest.take(8).joinToString("") { "%02x".format(it) } + "-" + name
}
