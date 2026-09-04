package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.style.BaseStyle

class DefaultMapRuntimeTest {
  @Test
  fun closing_the_default_runtime_is_permanent() = runTest {
    val cacheFile = FfiTestPlatform.createCacheFile()
    try {
      DefaultMapRuntime.configure(MapRuntimeOptions(cacheFile = cacheFile))
      val first = DefaultMapRuntime.instance
      first.close()
      first.awaitClosed()

      val second = DefaultMapRuntime.instance

      assertSame(first, second)
      assertTrue(second.isClosed)
      assertFailsWith<IllegalStateException> { second.createMapState(BaseStyle.Demo) }
    } finally {
      DefaultMapRuntime.resetForTest()
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
  }

  @Test
  fun configuring_after_the_default_runtime_exists_fails() {
    val cacheFile = FfiTestPlatform.createCacheFile()
    try {
      DefaultMapRuntime.configure(MapRuntimeOptions(cacheFile = cacheFile))
      DefaultMapRuntime.instance
      assertFailsWith<IllegalStateException> {
        DefaultMapRuntime.configure(MapRuntimeOptions(cacheFile = cacheFile))
      }
    } finally {
      DefaultMapRuntime.resetForTest()
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
  }
}
