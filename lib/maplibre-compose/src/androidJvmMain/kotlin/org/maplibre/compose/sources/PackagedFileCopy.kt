package org.maplibre.compose.sources

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One lock per URI, so two callers in one process read and copy the same resource once. */
private val copyLocks = ConcurrentHashMap<String, Mutex>()

/** Copies this process has returned. They stay on disk for the life of the process. */
private val copiesInUse: MutableSet<String> = ConcurrentHashMap.newKeySet()

/** A copy that no call has used for this long is deleted. */
private val UNUSED_COPY_LIFETIME = 30.days

/**
 * Returns a copy of the packaged resource that [open] reads, in [directory].
 *
 * The copy is named by the SHA-256 digest of its content, so a copy is complete and correct
 * whenever it exists, a changed resource gets a new copy, and two processes that copy the same
 * resource at the same time write the same file. Every call reads the resource once to identify it,
 * and a call that finds no copy reads it a second time to write one. Copies that no call has used
 * for [UNUSED_COPY_LIFETIME] are deleted, except copies this process has returned.
 */
internal suspend fun copyPackagedFile(
  uri: String,
  directory: File,
  open: () -> InputStream,
): File {
  val lock = copyLocks.getOrPut(uri) { Mutex() }
  return lock.withLock {
    withContext(Dispatchers.IO) {
      // Another process may evict a copy between finding it and marking it used, so try twice.
      repeat(2) {
        val copy = findOrWriteCopy(directory, open)
        copiesInUse += copy.absolutePath
        copy.setLastModified(System.currentTimeMillis())
        if (copy.isFile) {
          deleteUnusedCopies(directory)
          return@withContext copy
        }
      }
      error("The copy of $uri disappeared while it was being resolved")
    }
  }
}

private fun findOrWriteCopy(directory: File, open: () -> InputStream): File {
  val existing = File(directory, open().use { it.sha256Hex(to = null) } + ".mbtiles")
  if (existing.isFile) return existing
  directory.mkdirs()
  val temporary = File.createTempFile("copy", ".part", directory)
  try {
    // The name comes from the bytes written, in case the resource changed since the first read.
    val digest = open().use { input -> temporary.outputStream().use { input.sha256Hex(to = it) } }
    val destination = File(directory, "$digest.mbtiles")
    // A rename onto a copy that another process just finished may fail; that copy is the same
    // bytes.
    if (!destination.isFile && !temporary.renameTo(destination) && !destination.isFile) {
      error("Could not move $temporary to $destination")
    }
    return destination
  } finally {
    temporary.delete()
  }
}

/** Digests the stream, and writes it to [to] when given. */
private fun InputStream.sha256Hex(to: OutputStream?): String {
  val digest = MessageDigest.getInstance("SHA-256")
  val buffer = ByteArray(64 * 1024)
  while (true) {
    val read = read(buffer)
    if (read < 0) break
    digest.update(buffer, 0, read)
    to?.write(buffer, 0, read)
  }
  return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun deleteUnusedCopies(directory: File) {
  val cutoff = System.currentTimeMillis() - UNUSED_COPY_LIFETIME.inWholeMilliseconds
  directory
    .listFiles { file ->
      file.isFile && file.absolutePath !in copiesInUse && file.lastModified() < cutoff
    }
    ?.forEach { it.delete() }
}
