package org.maplibre.compose.resource

import java.nio.file.Path
import java.util.concurrent.Semaphore
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
  ) {
    val writePermit = Semaphore(1, true)
  }

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
        return lease(options, existing)
      }
    }
    return synchronized(entries) { lease(options, checkNotNull(entries[options.cachePath])) }
  }

  /** Acquires this database's fair, process-wide cache-budget write permit. */
  fun acquireWritePermit(path: Path): MlnFfiCacheDatabaseWritePermit {
    val normalizedPath = path.toAbsolutePath().normalize()
    val entry =
      synchronized(entries) {
        checkNotNull(entries[normalizedPath]) {
          "Cache database '$normalizedPath' has no live runtime to update"
        }
      }
    entry.writePermit.acquire()
    return writePermit(entry)
  }

  /** Number of writers waiting behind the active permit; deterministic test/diagnostic seam. */
  internal fun queuedWriteCount(path: Path): Int {
    val normalizedPath = path.toAbsolutePath().normalize()
    return synchronized(entries) { entries[normalizedPath]?.writePermit?.queueLength ?: 0 }
  }

  private fun lease(options: MlnFfiRuntimeOptions, entry: Entry): MlnFfiCacheDatabaseLease =
    MlnFfiCacheDatabaseLease(
      options = options,
      acquireWritePermitAction = {
        entry.writePermit.acquire()
        writePermit(entry)
      },
      release = { release(options.cachePath) },
    )

  private fun writePermit(entry: Entry): MlnFfiCacheDatabaseWritePermit =
    MlnFfiCacheDatabaseWritePermit(
      readEffectiveSize = { synchronized(entries) { entry.effectiveMaximumCacheSizeBytes } },
      commitEffectiveSize = { size ->
        synchronized(entries) { entry.effectiveMaximumCacheSizeBytes = size }
      },
      release = entry.writePermit::release,
    )

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
  private val acquireWritePermitAction: () -> MlnFfiCacheDatabaseWritePermit,
  private val release: () -> Unit,
) : AutoCloseable {
  private val closed = AtomicBoolean(false)

  fun acquireWritePermit(): MlnFfiCacheDatabaseWritePermit {
    check(!closed.get()) { "Cannot mutate the cache budget through a closed runtime lease" }
    return acquireWritePermitAction()
  }

  override fun close() {
    if (closed.compareAndSet(false, true)) release()
  }
}

/** Exclusive ownership of one cache-budget mutation, including its native completion. */
internal class MlnFfiCacheDatabaseWritePermit(
  private val readEffectiveSize: () -> Long?,
  private val commitEffectiveSize: (Long) -> Unit,
  private val release: () -> Unit,
) : AutoCloseable {
  private val closed = AtomicBoolean(false)

  val effectiveMaximumCacheSizeBytes: Long?
    get() {
      check(!closed.get()) { "Cannot read a released cache-budget permit" }
      return readEffectiveSize()
    }

  fun commit(sizeBytes: Long) {
    check(!closed.get()) { "Cannot commit through a released cache-budget permit" }
    commitEffectiveSize(sizeBytes)
  }

  override fun close() {
    if (closed.compareAndSet(false, true)) release()
  }
}

private fun Long?.describeBudget(): String = this?.let { "$it bytes" } ?: "MapLibre's default"
