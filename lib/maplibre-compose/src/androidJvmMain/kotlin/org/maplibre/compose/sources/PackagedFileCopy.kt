package org.maplibre.compose.sources

import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One lock per destination, so two callers that want the same file copy it once. */
private val copyLocks = ConcurrentHashMap<String, Mutex>()

/**
 * Copies a packaged resource to [destination] unless a copy with the same [stamp] is already there,
 * and returns [destination].
 *
 * [stamp] identifies the package version that the resource came from. The copy is written to a
 * temporary file and moved into place, so a reader sees either the old copy or the new one.
 */
internal suspend fun copyPackagedFile(
  destination: File,
  stamp: String,
  open: () -> InputStream,
): File {
  val lock = copyLocks.getOrPut(destination.absolutePath) { Mutex() }
  lock.withLock {
    withContext(Dispatchers.IO) {
      val stampFile = File(destination.path + ".stamp")
      val current = destination.isFile && stampFile.isFile && stampFile.readText() == stamp
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
        stampFile.writeText(stamp)
      }
    }
  }
  return destination
}

/** The file name for the copy of [uri]: its last path segment, made unique by the whole URI. */
internal fun packagedCopyName(uri: String): String {
  val name = uri.substringAfterLast('/').substringBefore('?').ifEmpty { "tiles.mbtiles" }
  return Integer.toHexString(uri.hashCode()) + "-" + name
}
