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
  fun a_failed_test_closes_its_explicit_runtime_and_maps() = runTest {
    val cacheFile = FfiTestPlatform.createCacheFile()
    val failure = AssertionError("test body failed")
    lateinit var runtime: MapRuntime
    lateinit var state: MapState
    try {
      val reported =
        assertFailsWith<AssertionError> {
          withTestRuntime(MapRuntimeOptions(cacheFile = cacheFile)) {
            runtime = it
            state = it.createMapState(BaseStyle.Empty)
            throw failure
          }
        }
      assertSame(failure, reported)
      assertTrue(runtime.isClosed)
      assertTrue(state.isClosed)
      runtime.awaitClosed()
      state.awaitClosed()
    } finally {
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
  }

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
