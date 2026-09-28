package org.maplibre.compose.desktop.bridge

import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.EXTMetalObjects.VK_EXT_METAL_OBJECTS_EXTENSION_NAME
import org.lwjgl.vulkan.EXTMetalObjects.VK_STRUCTURE_TYPE_EXPORT_METAL_DEVICE_INFO_EXT
import org.lwjgl.vulkan.EXTMetalObjects.VK_STRUCTURE_TYPE_EXPORT_METAL_OBJECTS_INFO_EXT
import org.lwjgl.vulkan.EXTMetalObjects.vkExportMetalObjectsEXT
import org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
import org.lwjgl.vulkan.VkExportMetalDeviceInfoEXT
import org.lwjgl.vulkan.VkExportMetalObjectsInfoEXT
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.NativeHandle

/** A MoltenVK device that renders on [metalDevice], the `MTLDevice` Compose draws with. */
internal fun VulkanDevice.Companion.forMetalDevice(metalDevice: Long): VulkanDevice =
  create(setOf(VK_EXT_METAL_OBJECTS_EXTENSION_NAME)) { _, device ->
    MemoryStack.stackPush().use { stack ->
      val info =
        VkExportMetalDeviceInfoEXT.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_EXPORT_METAL_DEVICE_INFO_EXT)
      val objects =
        VkExportMetalObjectsInfoEXT.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_EXPORT_METAL_OBJECTS_INFO_EXT)
          .pNext(info.address())
      vkExportMetalObjectsEXT(device, objects)
      info.mtlDevice() == metalDevice
    }
  }

/** A `VkImage` aliasing [texture], the `MTLTexture` MapLibre renders into, at [extent]. */
internal fun VulkanDevice.importMetalTexture(texture: NativeHandle, extent: MapExtent) =
  VulkanImage.create(
    this,
    extent,
    VK_FORMAT_B8G8R8A8_UNORM,
    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
    VulkanImageMemory.MetalTexture(texture),
  )
