package org.maplibre.compose.map

import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.maplibre.compose.mlnffi.AndroidMlnFfiPlatform
import org.maplibre.compose.style.BaseStyle

@OptIn(DelicateMapApi::class)
class AndroidExplicitRuntimeTest {
  @Test
  fun explicit_runtime_initializes_android_platform() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val cacheDirectory = context.cacheDir.resolve("explicit-runtime-${System.nanoTime()}")
    check(cacheDirectory.mkdirs()) { "Could not create test directory $cacheDirectory" }
    AndroidMlnFfiPlatform.resetForTest()
    val runtime =
      createMapRuntime(
        MapRuntimeOptions(
          context = context,
          cacheFile = cacheDirectory.resolve("cache.db"),
          logger = null,
        )
      )
    val state = runtime.createMapState(initialBaseStyle = BaseStyle.Empty)

    try {
      assertSame(context.applicationContext, AndroidMlnFfiPlatform.applicationContext)
      assertTrue(
        state.withPlatformMap {
          map.hashCode()
          true
        }
      )
    } finally {
      runtime.close()
      runtime.awaitClosed()
      cacheDirectory.deleteRecursively()
    }
  }
}
