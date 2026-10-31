package org.maplibre.compose.mlnffi

import android.hardware.HardwareBuffer
import androidx.annotation.RequiresApi
import org.maplibre.compose.map.MapExtent

/** Map buffers imported as Vulkan images on a device of their own. */
@RequiresApi(33)
internal class AndroidVulkanMapBuffers private constructor(private val handle: Long) :
  AndroidMapBuffers() {
  private val contextHandles = handle.toVulkanContextHandles()

  override val bufferTransform: Int
    get() = 0

  override fun import(
    buffers: List<HardwareBuffer>,
    extent: MapExtent,
    generation: Long,
  ): ImportedRing {
    val images = mutableListOf<Long>()
    try {
      buffers.forEach {
        images +=
          AndroidVulkanNativeBridge.importHardwareBuffer(
            handle,
            it,
            extent.physicalWidth,
            extent.physicalHeight,
          )
      }
    } catch (error: Throwable) {
      images.forEach { AndroidVulkanNativeBridge.destroyImage(handle, it) }
      throw error
    }
    return object : ImportedRing {
      override val target =
        VulkanImageTarget(
          context = contextHandles,
          images =
            images.map {
              VulkanRingImage(
                image = NativeHandle(AndroidVulkanNativeBridge.image(it)),
                imageView = NativeHandle(AndroidVulkanNativeBridge.imageView(it)),
              )
            },
          format = VK_FORMAT_R8G8B8A8_UNORM,
          // MapLibre draws every pixel of each frame.
          initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
          // SurfaceFlinger reads the image outside Vulkan.
          finalLayout = VK_IMAGE_LAYOUT_GENERAL,
          extent = extent,
          generation = generation,
        )

      override fun close() = images.forEach { AndroidVulkanNativeBridge.destroyImage(handle, it) }
    }
  }

  override fun closeContext() {
    AndroidVulkanNativeBridge.destroy(handle)
  }

  companion object {
    private const val VK_FORMAT_R8G8B8A8_UNORM = 37
    private const val VK_IMAGE_LAYOUT_UNDEFINED = 0
    private const val VK_IMAGE_LAYOUT_GENERAL = 1

    fun create(): AndroidVulkanMapBuffers =
      try {
        AndroidVulkanMapBuffers(AndroidVulkanNativeBridge.createForHardwareBuffers())
      } catch (error: UnsatisfiedLinkError) {
        throw IllegalStateException(
          "The Vulkan host requires the maplibre-compose-runtime-vulkan-android runtime",
          error,
        )
      }
  }
}
