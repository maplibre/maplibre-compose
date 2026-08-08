package org.maplibre.compose.resource

import java.nio.file.Paths
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
}
