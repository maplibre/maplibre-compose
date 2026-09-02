package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.style.BaseStyle

class DefaultMapRuntimeTest {
  @Test
  fun closing_the_default_runtime_is_permanent() = runTest {
    val cacheFile = FfiTestPlatform.createCacheFile()
    try {
      val first = DefaultMapRuntime.getOrCreate {
        MlnFfiRuntimeOptions(cacheFile = cacheFile, maximumCacheSizeBytes = null)
      }
      first.close()
      first.awaitClosed()

      var createdReplacement = false
      val second = DefaultMapRuntime.getOrCreate {
        createdReplacement = true
        MlnFfiRuntimeOptions(cacheFile = cacheFile, maximumCacheSizeBytes = 1)
      }

      assertSame(first, second)
      assertFalse(createdReplacement)
      assertTrue(second.isClosed)
      assertFailsWith<MapRuntimeClosedException> { second.createMapState(BaseStyle.Demo) }
    } finally {
      DefaultMapRuntime.resetForTest()
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
  }
}
