package org.maplibre.compose.runtime.vulkan

import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.maplibre.compose.android.AndroidRuntimeOptions
import org.maplibre.compose.map.MapSnapshotRequest
import org.maplibre.compose.map.createMapRuntime
import org.maplibre.compose.style.BaseStyle

class VulkanSnapshotterTest {
  @Test
  fun captures_scaled_image_with_vulkan_runtime(): Unit = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val cacheDirectory = context.cacheDir.resolve("vulkan-snapshot-${System.nanoTime()}")
    check(cacheDirectory.mkdirs()) { "Could not create $cacheDirectory" }
    val cacheFile = cacheDirectory.resolve("cache.db")
    val options = AndroidRuntimeOptions(context = context, cacheFile = cacheFile)
    val runtime = createMapRuntime(options)

    try {
      val snapshotter = runtime.createSnapshotter(BACKGROUND_STYLE)
      try {
        val capture = runCatching {
          snapshotter.capture(MapSnapshotRequest(width = 8, height = 6, density = 2f))
        }
        if (capture.exceptionOrNull()?.message == NO_VULKAN_PHYSICAL_DEVICES) {
          assumeTrue("The test device exposes no Vulkan physical device", false)
        }
        val image = capture.getOrThrow()
        assertEquals(16, image.width)
        assertEquals(12, image.height)
        val pixel = IntArray(1)
        image.readPixels(pixel, startX = 8, startY = 6, width = 1, height = 1)
        assertEquals(0xff336699.toInt(), pixel.single())
      } finally {
        snapshotter.close()
        snapshotter.awaitClosed()
      }
    } finally {
      runtime.close()
      runtime.awaitClosed()
      cacheDirectory.deleteRecursively()
    }
  }

  private companion object {
    const val NO_VULKAN_PHYSICAL_DEVICES = "Vulkan instance exposes no physical devices"

    val BACKGROUND_STYLE =
      BaseStyle.Json(
        """
        {"version":8,"sources":{},"layers":[
          {"id":"background","type":"background","paint":{"background-color":"#336699"}}
        ]}
        """
          .trimIndent()
      )
  }
}
