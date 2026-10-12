package org.maplibre.compose.map

import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.Test
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.maplibre.compose.mlnffi.AndroidMlnFfiPlatform

class AndroidExplicitRuntimeTest {
  @Test
  fun explicit_runtime_uses_the_application_context() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val cacheDirectory = context.cacheDir.resolve("explicit-runtime-${System.nanoTime()}")
    check(cacheDirectory.mkdirs()) { "Could not create test directory $cacheDirectory" }
    val runtime = createMapRuntime {
      cacheFile = Path(cacheDirectory.resolve("cache.db").absolutePath)
    }

    try {
      assertSame(context.applicationContext, AndroidMlnFfiPlatform.applicationContext)
    } finally {
      runtime.close()
      runtime.awaitClosed()
      cacheDirectory.deleteRecursively()
    }
  }
}
