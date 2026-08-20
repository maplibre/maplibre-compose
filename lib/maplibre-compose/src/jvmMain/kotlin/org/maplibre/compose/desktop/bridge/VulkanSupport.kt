package org.maplibre.compose.desktop.bridge

import java.nio.ByteBuffer
import org.lwjgl.PointerBuffer
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK
import org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
import org.lwjgl.vulkan.VK10.VK_QUEUE_GRAPHICS_BIT
import org.lwjgl.vulkan.VK10.VK_SUCCESS
import org.lwjgl.vulkan.VK10.vkEnumerateDeviceExtensionProperties
import org.lwjgl.vulkan.VK10.vkEnumerateInstanceExtensionProperties
import org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceMemoryProperties
import org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties
import org.lwjgl.vulkan.VkExtensionProperties
import org.lwjgl.vulkan.VkPhysicalDevice
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties
import org.lwjgl.vulkan.VkQueueFamilyProperties

internal fun isWindowsDesktop(): Boolean =
  System.getProperty("os.name")?.lowercase().orEmpty().contains("win")

internal fun checkVulkan(status: Int, operation: String) {
  check(status == VK_SUCCESS) { "$operation failed with Vulkan status $status" }
}

internal fun ensureVulkanFunctionProvider() {
  @Suppress("SENSELESS_COMPARISON")
  if (VK.getFunctionProvider() == null) {
    VK.create()
  }
}

internal fun vulkanFunctionAddress(name: String): Long {
  ensureVulkanFunctionProvider()
  return VK.getFunctionProvider().getFunctionAddress(name)
}

internal fun MemoryStack.vulkanInstanceExtensions(): Set<String> {
  val count = mallocInt(1)
  checkVulkan(
    vkEnumerateInstanceExtensionProperties(null as String?, count, null),
    "vkEnumerateInstanceExtensionProperties(count)",
  )
  if (count[0] == 0) return emptySet()
  // Heap: a typical ICD reports enough extensions to overflow LWJGL's 32 KiB thread stack.
  val props = VkExtensionProperties.calloc(count[0])
  try {
    checkVulkan(
      vkEnumerateInstanceExtensionProperties(null as String?, count, props),
      "vkEnumerateInstanceExtensionProperties",
    )
    return buildSet { props.forEach { add(it.extensionNameString()) } }
  } finally {
    props.free()
  }
}

internal fun MemoryStack.vulkanDeviceExtensions(device: VkPhysicalDevice): Set<String> {
  val count = mallocInt(1)
  checkVulkan(
    vkEnumerateDeviceExtensionProperties(device, null as String?, count, null),
    "vkEnumerateDeviceExtensionProperties(count)",
  )
  if (count[0] == 0) return emptySet()
  val props = VkExtensionProperties.calloc(count[0])
  try {
    checkVulkan(
      vkEnumerateDeviceExtensionProperties(device, null as String?, count, props),
      "vkEnumerateDeviceExtensionProperties",
    )
    return buildSet { props.forEach { add(it.extensionNameString()) } }
  } finally {
    props.free()
  }
}

internal fun MemoryStack.vulkanStringBuffer(values: Set<String>): PointerBuffer {
  val buffer = mallocPointer(values.size)
  for (value in values) {
    buffer.put(UTF8(value))
  }
  return buffer.flip()
}

internal fun MemoryStack.findVulkanGraphicsQueueFamily(device: VkPhysicalDevice): Int {
  val count = mallocInt(1)
  vkGetPhysicalDeviceQueueFamilyProperties(device, count, null)
  val families = VkQueueFamilyProperties.calloc(count[0], this)
  vkGetPhysicalDeviceQueueFamilyProperties(device, count, families)
  for (index in 0..<families.capacity()) {
    if ((families[index].queueFlags() and VK_QUEUE_GRAPHICS_BIT) != 0) return index
  }
  return -1
}

internal fun findVulkanDeviceLocalMemoryType(
  device: VkPhysicalDevice,
  typeBits: Int,
  errorMessage: String,
): Int {
  MemoryStack.stackPush().use { stack ->
    val properties = VkPhysicalDeviceMemoryProperties.calloc(stack)
    vkGetPhysicalDeviceMemoryProperties(device, properties)
    for (index in 0..<properties.memoryTypeCount()) {
      val supported = (typeBits and (1 shl index)) != 0
      val deviceLocal =
        (properties.memoryTypes(index).propertyFlags() and VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) ==
          VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
      if (supported && deviceLocal) return index
    }
  }
  error(errorMessage)
}

internal fun ByteBuffer.toUuidHex(length: Int): String =
  (0..<length).joinToString(separator = "") { index ->
    (get(index).toInt() and 0xff).toString(16).padStart(2, '0')
  }
