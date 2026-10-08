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
    val cache = FfiTestPlatform.createCacheFile()
    val failure = AssertionError("test body failed")
    lateinit var runtime: MapRuntime
    lateinit var state: MapState
    try {
      val reported =
        assertFailsWith<AssertionError> {
          withTestRuntime(configure = { cacheFile = cache }) {
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
      FfiTestPlatform.deleteCacheFile(cache)
    }
  }

  @Test
  fun closing_the_default_runtime_is_permanent() = runTest {
    val cache = FfiTestPlatform.createCacheFile()
    try {
      DefaultMapRuntime.configure { cacheFile = cache }
      val first = DefaultMapRuntime.instance
      first.close()
      first.awaitClosed()

      val second = DefaultMapRuntime.instance

      assertSame(first, second)
      assertTrue(second.isClosed)
      assertFailsWith<IllegalStateException> { second.createMapState(BaseStyle.Demo) }
    } finally {
      DefaultMapRuntime.resetForTest()
      FfiTestPlatform.deleteCacheFile(cache)
    }
  }

  @Test
  fun configuring_after_the_default_runtime_exists_fails() {
    val cache = FfiTestPlatform.createCacheFile()
    try {
      DefaultMapRuntime.configure { cacheFile = cache }
      DefaultMapRuntime.instance
      assertFailsWith<IllegalStateException> {
        DefaultMapRuntime.configure { cacheFile = cache }
      }
    } finally {
      DefaultMapRuntime.resetForTest()
      FfiTestPlatform.deleteCacheFile(cache)
    }
  }
}
