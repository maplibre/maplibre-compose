package org.maplibre.compose.mlnffi

import android.view.Surface
import org.maplibre.compose.map.MapExtent

/**
 * The Vulkan instance, device, and queue a surface session renders with, and the `VkSurfaceKHR`
 * built from the host [Surface].
 *
 * A Vulkan context cannot outlive its window: the host tears it down with the session when the
 * surface goes away, and the next surface builds both anew. A resize keeps it, since the
 * `VkSurfaceKHR` keeps naming the same window.
 */
internal class AndroidVulkanContext private constructor(private var handle: Long) :
  AndroidMapGraphicsContext {

  override fun target(extent: MapExtent, generation: Long): MlnFfiRenderTarget =
    VulkanSurfaceTarget(
      context = contextHandles,
      surface = surfaceHandle,
      extent = extent,
      generation = generation,
    )

  private val contextHandles: VulkanContextHandles
    get() =
      VulkanContextHandles(
        instance = NativeHandle(AndroidVulkanNativeBridge.instance(handle)),
        physicalDevice = NativeHandle(AndroidVulkanNativeBridge.physicalDevice(handle)),
        device = NativeHandle(AndroidVulkanNativeBridge.device(handle)),
        graphicsQueue = NativeHandle(AndroidVulkanNativeBridge.graphicsQueue(handle)),
        graphicsQueueFamilyIndex = AndroidVulkanNativeBridge.graphicsQueueFamilyIndex(handle),
        getInstanceProcAddr = NativeHandle(AndroidVulkanNativeBridge.getInstanceProcAddr()),
        getDeviceProcAddr = NativeHandle(AndroidVulkanNativeBridge.getDeviceProcAddr()),
      )

  private val surfaceHandle: NativeHandle
    get() = NativeHandle(AndroidVulkanNativeBridge.surface(handle))

  override fun close() {
    if (handle == 0L) return
    AndroidVulkanNativeBridge.destroy(handle)
    handle = 0L
  }

  companion object {
    fun create(surface: Surface): AndroidVulkanContext =
      try {
        AndroidVulkanContext(AndroidVulkanNativeBridge.create(surface))
      } catch (error: UnsatisfiedLinkError) {
        throw IllegalStateException(
          "The Android Vulkan host needs the maplibre-compose-runtime-vulkan-android runtime, " +
            "which packages its Vulkan loader shim",
          error,
        )
      }
  }
}

/** JNI bindings into the Vulkan loader shim the Vulkan runtime artifact packages. */
private object AndroidVulkanNativeBridge {
  init {
    System.loadLibrary("maplibre_compose_vulkan")
  }

  /** Builds the context for [surface], or throws with the Vulkan failure. */
  external fun create(surface: Surface): Long

  external fun destroy(handle: Long)

  /** `VkInstance`. */
  external fun instance(handle: Long): Long

  /** `VkSurfaceKHR`. */
  external fun surface(handle: Long): Long

  /** `VkPhysicalDevice`. */
  external fun physicalDevice(handle: Long): Long

  /** `VkDevice`. */
  external fun device(handle: Long): Long

  /** `VkQueue` for graphics work. */
  external fun graphicsQueue(handle: Long): Long

  external fun graphicsQueueFamilyIndex(handle: Long): Int

  /** `PFN_vkGetInstanceProcAddr`. */
  external fun getInstanceProcAddr(): Long

  /** `PFN_vkGetDeviceProcAddr`. */
  external fun getDeviceProcAddr(): Long
}
