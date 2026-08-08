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
 * the effective limit. A successful runtime mutation changes the effective value reapplied to later
 * runtimes without changing that configuration identity.
 */
internal object MlnFfiCacheDatabaseRegistry {
  private class Entry(
    val configuredMaximumCacheSizeBytes: Long?,
    var effectiveMaximumCacheSizeBytes: Long?,
    var leases: Int,
  )

  private val entries = mutableMapOf<Path, Entry>()

  fun acquire(rawOptions: MlnFfiRuntimeOptions): MlnFfiCacheDatabaseLease {
    val options = rawOptions.normalized()
    synchronized(entries) {
      val existing = entries[options.cachePath]
      if (existing == null) {
        entries[options.cachePath] =
          Entry(
            configuredMaximumCacheSizeBytes = options.maximumCacheSizeBytes,
            effectiveMaximumCacheSizeBytes = options.maximumCacheSizeBytes,
            leases = 1,
          )
      } else {
        check(existing.configuredMaximumCacheSizeBytes == options.maximumCacheSizeBytes) {
          "Cache database '${options.cachePath}' is already open with configured ambient-cache " +
            "budget " +
            "${existing.configuredMaximumCacheSizeBytes.describeBudget()}, but another runtime " +
            "requested " +
            options.maximumCacheSizeBytes.describeBudget()
        }
        existing.leases++
        return MlnFfiCacheDatabaseLease(
          options.copy(maximumCacheSizeBytes = existing.effectiveMaximumCacheSizeBytes)
        ) {
          release(options.cachePath)
        }
      }
    }
    return MlnFfiCacheDatabaseLease(options) { release(options.cachePath) }
  }

  /** Keeps runtimes opened later from reapplying the configuration that preceded this mutation. */
  fun updateEffectiveMaximumCacheSize(path: Path, sizeBytes: Long) {
    synchronized(entries) {
      val normalizedPath = path.toAbsolutePath().normalize()
      val entry =
        checkNotNull(entries[normalizedPath]) {
          "Cache database '$normalizedPath' has no live runtime to update"
        }
      entry.effectiveMaximumCacheSizeBytes = sizeBytes
    }
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
