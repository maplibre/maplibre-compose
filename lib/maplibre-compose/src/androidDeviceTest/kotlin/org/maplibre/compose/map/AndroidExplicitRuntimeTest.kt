package org.maplibre.compose.map

import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.maplibre.compose.android.AndroidRuntimeOptions
import org.maplibre.compose.mlnffi.AndroidMlnFfiPlatform
import org.maplibre.compose.mlnffi.MlnFfiApplication
import org.maplibre.compose.style.BaseStyle

@OptIn(DelicateMapApi::class)
class AndroidExplicitRuntimeTest {
  @Test
  fun explicit_runtime_initializes_android_without_configuring_the_process_runtime() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val cacheDirectory = context.cacheDir.resolve("explicit-runtime-${System.nanoTime()}")
    check(cacheDirectory.mkdirs()) { "Could not create test directory $cacheDirectory" }
    check(MlnFfiApplication.resetForTest()) { "Could not reset the process runtime" }
    AndroidMlnFfiPlatform.resetForTest()
    val runtime =
      createMapRuntime(
        AndroidRuntimeOptions(
          context = context,
          cacheFile = cacheDirectory.resolve("cache.db"),
          logger = null,
        )
      )
    val state = runtime.createMapState(initialBaseStyle = BaseStyle.Empty)

    try {
      assertSame(context.applicationContext, AndroidMlnFfiPlatform.applicationContext)
      assertFalse(MlnFfiApplication.isConfigured)
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
