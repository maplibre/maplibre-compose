package org.maplibre.compose.mlnffi

import android.view.Surface
import org.maplibre.compose.map.MapExtent

/**
 * The Vulkan instance, device, queue, and `VkSurfaceKHR` for one host [Surface]. A new surface
 * requires a new context; a resize reuses this one.
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
    get() = handle.toVulkanContextHandles()

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
          "The Vulkan host requires the maplibre-compose-runtime-vulkan-android runtime",
          error,
        )
      }
  }
}

/** A Vulkan device and graphics queue that require no presentation surface. */
internal class AndroidVulkanOffscreenContext private constructor(private var handle: Long) :
  AutoCloseable {

  val handles: VulkanContextHandles
    get() = handle.toVulkanContextHandles()

  override fun close() {
    if (handle == 0L) return
    AndroidVulkanNativeBridge.destroy(handle)
    handle = 0L
  }

  companion object {
    fun create(): AndroidVulkanOffscreenContext =
      try {
        AndroidVulkanOffscreenContext(AndroidVulkanNativeBridge.createOffscreen())
      } catch (error: UnsatisfiedLinkError) {
        throw IllegalStateException(
          "Vulkan snapshots require the maplibre-compose-runtime-vulkan-android runtime",
          error,
        )
      }
  }
}

private fun Long.toVulkanContextHandles() =
  VulkanContextHandles(
    instance = NativeHandle(AndroidVulkanNativeBridge.instance(this)),
    physicalDevice = NativeHandle(AndroidVulkanNativeBridge.physicalDevice(this)),
    device = NativeHandle(AndroidVulkanNativeBridge.device(this)),
    graphicsQueue = NativeHandle(AndroidVulkanNativeBridge.graphicsQueue(this)),
    graphicsQueueFamilyIndex = AndroidVulkanNativeBridge.graphicsQueueFamilyIndex(this),
    getInstanceProcAddr = NativeHandle(AndroidVulkanNativeBridge.getInstanceProcAddr()),
    getDeviceProcAddr = NativeHandle(AndroidVulkanNativeBridge.getDeviceProcAddr()),
  )

/** JNI bindings for the native Vulkan loader shim. */
private object AndroidVulkanNativeBridge {
  init {
    System.loadLibrary("maplibre_compose_vulkan")
  }

  /** Builds the context for [surface], or throws with the Vulkan failure. */
  external fun create(surface: Surface): Long

  /** Builds a graphics context that requires no presentation surface. */
  external fun createOffscreen(): Long

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
