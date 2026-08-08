package org.maplibre.compose.mlnffi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MlnFfiApplicationTest {
  @Test
  fun failed_startup_is_not_published_and_configuration_can_be_retried() {
    val cachePath = FfiTestPlatform.createCachePath()
    val options = MlnFfiRuntimeOptions(cachePath)
    try {
      val failure = IllegalStateException("deliberate startup failure")
      val thrown =
        assertFailsWith<IllegalStateException> {
          MlnFfiApplication.configure(options) { throw failure }
        }
      assertEquals(failure, thrown)
      assertFailsWith<IllegalStateException> { MlnFfiApplication.options }

      MlnFfiApplication.configure(options)
      MlnFfiApplication.configure(options)
      assertEquals(options.normalized(), MlnFfiApplication.options)
      assertFailsWith<IllegalStateException> {
        MlnFfiApplication.configure(options.copy(maximumCacheSizeBytes = 1))
      }
    } finally {
      MlnFfiApplication.resetForTest()
      FfiTestPlatform.deleteCachePath(cachePath)
    }
  }
}
