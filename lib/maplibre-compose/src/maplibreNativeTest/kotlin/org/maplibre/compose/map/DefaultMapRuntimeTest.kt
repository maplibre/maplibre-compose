package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.TestMapMain
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.runMapTest

class DefaultMapRuntimeTest {
  @Test
  fun closing_the_default_runtime_is_permanent() = runMapTest {
    val cache = FfiTestPlatform.createCacheFile()
    try {
      DefaultMapRuntime.configure {
        cacheFile = cache
        mainDispatcher = TestMapMain.dispatcher
      }
      val first = DefaultMapRuntime.instance
      first.close()
      first.awaitClosed()

      val second = DefaultMapRuntime.instance

      assertSame(first, second)
      assertTrue(second.isClosed)
      assertFailsWith<IllegalStateException> { second.createMapState(BaseStyle.Demo) }
    } finally {
      DefaultMapRuntime.clearForTest()?.awaitClosed()
      FfiTestPlatform.deleteCacheFile(cache)
    }
  }

  @Test
  fun configuring_after_the_default_runtime_exists_fails() = runMapTest {
    val cache = FfiTestPlatform.createCacheFile()
    try {
      DefaultMapRuntime.configure {
        cacheFile = cache
        mainDispatcher = TestMapMain.dispatcher
      }
      DefaultMapRuntime.instance
      assertFailsWith<IllegalStateException> {
        DefaultMapRuntime.configure {
          cacheFile = cache
          mainDispatcher = TestMapMain.dispatcher
        }
      }
    } finally {
      DefaultMapRuntime.clearForTest()?.awaitClosed()
      FfiTestPlatform.deleteCacheFile(cache)
    }
  }
}
