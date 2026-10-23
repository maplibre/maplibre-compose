package org.maplibre.compose.map

import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.Test
import kotlin.test.assertSame
import kotlinx.io.files.Path
import org.maplibre.compose.mlnffi.AndroidMlnFfiPlatform
import org.maplibre.compose.testing.runMapTest

class AndroidExplicitRuntimeTest {
  @Test
  fun explicit_runtime_uses_the_application_context() = runMapTest {
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
