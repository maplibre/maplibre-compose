package org.maplibre.compose.desktop.bridge

import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.EXTMetalObjects.VK_EXPORT_METAL_OBJECT_TYPE_METAL_DEVICE_BIT_EXT
import org.lwjgl.vulkan.EXTMetalObjects.VK_EXT_METAL_OBJECTS_EXTENSION_NAME
import org.lwjgl.vulkan.EXTMetalObjects.VK_STRUCTURE_TYPE_EXPORT_METAL_OBJECT_CREATE_INFO_EXT
import org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR
import org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME
import org.lwjgl.vulkan.KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO
import org.lwjgl.vulkan.VK10.VK_SUCCESS
import org.lwjgl.vulkan.VK10.VK_UUID_SIZE
import org.lwjgl.vulkan.VK10.vkCreateDevice
import org.lwjgl.vulkan.VK10.vkCreateInstance
import org.lwjgl.vulkan.VK10.vkDestroyDevice
import org.lwjgl.vulkan.VK10.vkDestroyInstance
import org.lwjgl.vulkan.VK10.vkDeviceWaitIdle
import org.lwjgl.vulkan.VK10.vkEnumeratePhysicalDevices
import org.lwjgl.vulkan.VK10.vkGetDeviceQueue
import org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1
import org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES
import org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2
import org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2
import org.lwjgl.vulkan.VkApplicationInfo
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkDeviceCreateInfo
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo
import org.lwjgl.vulkan.VkExportMetalObjectCreateInfoEXT
import org.lwjgl.vulkan.VkInstance
import org.lwjgl.vulkan.VkInstanceCreateInfo
import org.lwjgl.vulkan.VkPhysicalDevice
import org.lwjgl.vulkan.VkPhysicalDeviceIDProperties
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2
import org.lwjgl.vulkan.VkQueue
import org.maplibre.compose.mlnffi.NativeHandle
import org.maplibre.compose.mlnffi.VulkanContextHandles

/**
 * Owns the Vulkan instance, device, and graphics queue shared by desktop bridge implementations.
 */
internal class VulkanDevice private constructor() : AutoCloseable {
  private var instance: VkInstance? = null
  private var selected: VkDevice? = null
  private var queue: VkQueue? = null
  private var queueFamily = 0
  val device: VkDevice
    get() = checkNotNull(selected)

  val physicalDevice: VkPhysicalDevice
    get() = device.physicalDevice

  val handles: VulkanContextHandles
    get() =
      VulkanContextHandles(
        NativeHandle(checkNotNull(instance).address()),
        NativeHandle(physicalDevice.address()),
        NativeHandle(device.address()),
        NativeHandle(checkNotNull(queue).address()),
        queueFamily,
        NativeHandle(vulkanFunctionAddress("vkGetInstanceProcAddr")),
        NativeHandle(vulkanFunctionAddress("vkGetDeviceProcAddr")),
      )

  fun waitIdle() {
    selected?.let { checkVulkan(vkDeviceWaitIdle(it), "vkDeviceWaitIdle") }
  }

  private fun initialize(
    extensions: Set<String>,
    accepts: (VkPhysicalDevice, VkDevice) -> Boolean,
  ) {
    ensureVulkanFunctionProvider()
    MemoryStack.stackPush().use { stack ->
      val available = stack.vulkanInstanceExtensions()
      val portability = VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME in available
      val instanceExtensions =
        if (portability) setOf(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME) else emptySet()
      val app =
        VkApplicationInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
          .pApplicationName(stack.UTF8("maplibre-compose"))
          .apiVersion(VK_API_VERSION_1_1)
      val info =
        VkInstanceCreateInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
          .pApplicationInfo(app)
          .ppEnabledExtensionNames(stack.vulkanStringBuffer(instanceExtensions))
      if (portability) info.flags(VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR)
      if (VK_EXT_METAL_OBJECTS_EXTENSION_NAME in extensions) {
        info.pNext(
          VkExportMetalObjectCreateInfoEXT.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_EXPORT_METAL_OBJECT_CREATE_INFO_EXT)
            .exportObjectType(VK_EXPORT_METAL_OBJECT_TYPE_METAL_DEVICE_BIT_EXT)
            .address()
        )
      }
      val out = stack.mallocPointer(1)
      checkVulkan(vkCreateInstance(info, null, out), "vkCreateInstance")
      val instance = VkInstance(out[0], info).also { this.instance = it }
      val count = stack.mallocInt(1)
      checkVulkan(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices")
      val candidates = stack.mallocPointer(count[0])
      checkVulkan(
        vkEnumeratePhysicalDevices(instance, count, candidates),
        "vkEnumeratePhysicalDevices",
      )
      for (index in 0 until count[0]) {
        val physical = VkPhysicalDevice(candidates[index], instance)
        val supported = stack.vulkanDeviceExtensions(physical)
        if (!supported.containsAll(extensions)) continue
        val family = stack.findVulkanGraphicsQueueFamily(physical)
        if (family < 0) continue
        val enabled = extensions.toMutableSet()
        if (VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME in supported)
          enabled.add(VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME)
        val queues =
          VkDeviceQueueCreateInfo.calloc(1, stack)
            .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
            .queueFamilyIndex(family)
            .pQueuePriorities(stack.floats(1f))
        val deviceInfo =
          VkDeviceCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
            .pQueueCreateInfos(queues)
            .ppEnabledExtensionNames(stack.vulkanStringBuffer(enabled))
        if (vkCreateDevice(physical, deviceInfo, null, out) != VK_SUCCESS) continue
        val candidate = VkDevice(out[0], physical, deviceInfo)
        var accepted = false
        try {
          accepted = accepts(physical, candidate)
          if (accepted) {
            selected = candidate
            queueFamily = family
            vkGetDeviceQueue(candidate, family, 0, out)
            queue = VkQueue(out[0], candidate)
            return
          }
        } finally {
          if (!accepted) vkDestroyDevice(candidate, null)
        }
      }
      error("No Vulkan device supports $extensions and matches the Compose graphics device")
    }
  }

  override fun close() {
    selected?.let {
      vkDeviceWaitIdle(it)
      vkDestroyDevice(it, null)
    }
    selected = null
    queue = null
    instance?.let { vkDestroyInstance(it, null) }
    instance = null
  }

  companion object {
    fun create(
      extensions: Set<String> = emptySet(),
      accepts: (VkPhysicalDevice, VkDevice) -> Boolean = { _, _ -> true },
    ): VulkanDevice {
      val context = VulkanDevice()
      try {
        context.initialize(extensions, accepts)
        return context
      } catch (error: Throwable) {
        context.close()
        throw error
      }
    }
  }
}

internal fun vulkanDeviceUuid(candidate: VkPhysicalDevice): String =
  MemoryStack.stackPush().use { stack ->
    val id =
      VkPhysicalDeviceIDProperties.calloc(stack)
        .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES)
    val properties =
      VkPhysicalDeviceProperties2.calloc(stack)
        .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2)
        .pNext(id.address())
    vkGetPhysicalDeviceProperties2(candidate, properties)
    id.deviceUUID().toUuidHex(VK_UUID_SIZE)
  }

internal fun vulkanDeviceLuid(candidate: VkPhysicalDevice): Long =
  MemoryStack.stackPush().use { stack ->
    val id =
      VkPhysicalDeviceIDProperties.calloc(stack)
        .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES)
    val properties =
      VkPhysicalDeviceProperties2.calloc(stack)
        .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2)
        .pNext(id.address())
    vkGetPhysicalDeviceProperties2(candidate, properties)
    if (id.deviceLUIDValid()) id.deviceLUID().getLong(0) else 0L
  }
