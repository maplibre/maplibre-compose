package org.maplibre.compose.resource

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.normalized

/**
 * Coordinates the process's live users of one MapLibre cache database.
 *
 * MapLibre stores the ambient-cache budget in the database, not in an individual map. Allowing two
 * runtimes to open it with different configured budgets would therefore make creation order choose
 * the effective limit.
 */
internal object MlnFfiCacheDatabaseRegistry {
  private class Entry(val maximumCacheSizeBytes: Long?, var leases: Int)

  private val entries = mutableMapOf<Path, Entry>()

  fun acquire(rawOptions: MlnFfiRuntimeOptions): MlnFfiCacheDatabaseLease {
    val options = rawOptions.normalized()
    synchronized(entries) {
      val existing = entries[options.cachePath]
      if (existing == null) {
        entries[options.cachePath] = Entry(options.maximumCacheSizeBytes, leases = 1)
      } else {
        check(existing.maximumCacheSizeBytes == options.maximumCacheSizeBytes) {
          "Cache database '${options.cachePath}' is already open with ambient-cache budget " +
            "${existing.maximumCacheSizeBytes.describeBudget()}, but another runtime requested " +
            options.maximumCacheSizeBytes.describeBudget()
        }
        existing.leases++
      }
    }
    return MlnFfiCacheDatabaseLease(options) { release(options.cachePath) }
  }

  private fun release(path: Path) {
    synchronized(entries) {
      val entry = checkNotNull(entries[path]) { "Cache database '$path' has no live lease" }
      entry.leases--
      check(entry.leases >= 0) { "Cache database '$path' was released too many times" }
      if (entry.leases == 0) entries.remove(path)
    }
  }
}

/** One live runtime's claim on a normalized cache database configuration. */
internal class MlnFfiCacheDatabaseLease(
  val options: MlnFfiRuntimeOptions,
  private val release: () -> Unit,
) : AutoCloseable {
  private val closed = AtomicBoolean(false)

  override fun close() {
    if (closed.compareAndSet(false, true)) release()
  }
}

private fun Long?.describeBudget(): String = this?.let { "$it bytes" } ?: "MapLibre's default"
