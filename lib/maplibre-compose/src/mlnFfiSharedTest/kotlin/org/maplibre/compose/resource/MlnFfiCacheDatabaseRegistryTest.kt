package org.maplibre.compose.resource

import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions

class MlnFfiCacheDatabaseRegistryTest {

  @Test
  fun normalized_paths_with_matching_budgets_can_be_leased_together() {
    val direct = Paths.get("build", "cache-registry-test.db").toAbsolutePath()
    val aliased = direct.parent.resolve("subdirectory").resolve("..").resolve(direct.fileName)
    val first =
      MlnFfiCacheDatabaseRegistry.acquire(
        MlnFfiRuntimeOptions(direct, maximumCacheSizeBytes = 1024)
      )
    try {
      val second =
        MlnFfiCacheDatabaseRegistry.acquire(
          MlnFfiRuntimeOptions(aliased, maximumCacheSizeBytes = 1024)
        )
      try {
        assertEquals(direct.normalize(), first.options.cachePath)
        assertEquals(first.options, second.options)
      } finally {
        second.close()
      }
    } finally {
      first.close()
    }
  }

  @Test
  fun a_live_database_rejects_a_different_budget() {
    val path = Paths.get("build", "cache-registry-budget-test.db")
    val first =
      MlnFfiCacheDatabaseRegistry.acquire(MlnFfiRuntimeOptions(path, maximumCacheSizeBytes = null))
    try {
      assertFailsWith<IllegalStateException> {
        MlnFfiCacheDatabaseRegistry.acquire(
          MlnFfiRuntimeOptions(path, maximumCacheSizeBytes = 1024)
        )
      }
    } finally {
      first.close()
    }

    // The configuration belongs to the live runtimes, not permanently to the path.
    MlnFfiCacheDatabaseRegistry.acquire(MlnFfiRuntimeOptions(path, maximumCacheSizeBytes = 1024))
      .close()
  }

  @Test
  fun a_budget_mutation_is_reapplied_by_later_runtimes_without_changing_configuration_identity() {
    val path = Paths.get("build", "cache-registry-mutation-test.db")
    val configured = MlnFfiRuntimeOptions(path, maximumCacheSizeBytes = 1024)
    val first = MlnFfiCacheDatabaseRegistry.acquire(configured)
    try {
      first.acquireWritePermit().use { it.commit(2048) }

      val second = MlnFfiCacheDatabaseRegistry.acquire(configured)
      try {
        second.acquireWritePermit().use { assertEquals(2048, it.effectiveMaximumCacheSizeBytes) }
      } finally {
        second.close()
      }
      assertFailsWith<IllegalStateException> {
        MlnFfiCacheDatabaseRegistry.acquire(configured.copy(maximumCacheSizeBytes = 2048))
      }
    } finally {
      first.close()
    }
  }

  @Test
  fun concurrent_writers_are_fair_and_each_reads_the_latest_committed_size() {
    val path = Paths.get("build", "cache-registry-concurrent-test.db")
    val lease =
      MlnFfiCacheDatabaseRegistry.acquire(MlnFfiRuntimeOptions(path, maximumCacheSizeBytes = 1024))
    val first = lease.acquireWritePermit()
    val pool = Executors.newFixedThreadPool(2)
    try {
      val secondStarted = CountDownLatch(1)
      val second =
        pool.submit<Long?> {
          secondStarted.countDown()
          lease.acquireWritePermit().use { permit ->
            permit.effectiveMaximumCacheSizeBytes.also { permit.commit(4096) }
          }
        }
      check(secondStarted.await(5, TimeUnit.SECONDS))
      awaitQueuedWriters(path, 1)

      val third =
        pool.submit<Long?> { lease.acquireWritePermit().use { it.effectiveMaximumCacheSizeBytes } }
      awaitQueuedWriters(path, 2)

      first.commit(2048)
      first.close()

      assertEquals(2048, second.get(5, TimeUnit.SECONDS))
      assertEquals(4096, third.get(5, TimeUnit.SECONDS))
    } finally {
      first.close()
      pool.shutdownNow()
      lease.close()
    }
  }

  @Test
  fun an_uncommitted_failed_or_cancelled_write_releases_without_changing_the_effective_size() {
    val path = Paths.get("build", "cache-registry-abandoned-write-test.db")
    val lease =
      MlnFfiCacheDatabaseRegistry.acquire(MlnFfiRuntimeOptions(path, maximumCacheSizeBytes = 1024))
    try {
      lease.acquireWritePermit().close()
      lease.acquireWritePermit().use { assertEquals(1024, it.effectiveMaximumCacheSizeBytes) }
    } finally {
      lease.close()
    }
  }

  private fun awaitQueuedWriters(path: java.nio.file.Path, expected: Int) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (MlnFfiCacheDatabaseRegistry.queuedWriteCount(path) < expected) {
      check(System.nanoTime() < deadline) { "Timed out waiting for $expected cache-budget writers" }
      Thread.yield()
    }
  }
}
