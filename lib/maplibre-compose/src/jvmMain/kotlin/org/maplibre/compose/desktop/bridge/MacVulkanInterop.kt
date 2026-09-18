package org.maplibre.compose.desktop.bridge

import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.vulkan.EXTMetalObjects.VK_EXT_METAL_OBJECTS_EXTENSION_NAME
import org.lwjgl.vulkan.EXTMetalObjects.VK_STRUCTURE_TYPE_EXPORT_METAL_DEVICE_INFO_EXT
import org.lwjgl.vulkan.EXTMetalObjects.VK_STRUCTURE_TYPE_EXPORT_METAL_OBJECTS_INFO_EXT
import org.lwjgl.vulkan.EXTMetalObjects.VK_STRUCTURE_TYPE_IMPORT_METAL_TEXTURE_INFO_EXT
import org.lwjgl.vulkan.EXTMetalObjects.vkExportMetalObjectsEXT
import org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM
import org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED
import org.lwjgl.vulkan.VK10.VK_IMAGE_TILING_OPTIMAL
import org.lwjgl.vulkan.VK10.VK_IMAGE_TYPE_2D
import org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
import org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT
import org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D
import org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT
import org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO
import org.lwjgl.vulkan.VK10.vkCreateImage
import org.lwjgl.vulkan.VK10.vkCreateImageView
import org.lwjgl.vulkan.VK10.vkDestroyImage
import org.lwjgl.vulkan.VK10.vkDestroyImageView
import org.lwjgl.vulkan.VkExportMetalDeviceInfoEXT
import org.lwjgl.vulkan.VkExportMetalObjectsInfoEXT
import org.lwjgl.vulkan.VkExtent3D
import org.lwjgl.vulkan.VkImageCreateInfo
import org.lwjgl.vulkan.VkImageSubresourceRange
import org.lwjgl.vulkan.VkImageViewCreateInfo
import org.lwjgl.vulkan.VkImportMetalTextureInfoEXT
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.NativeHandle
import org.maplibre.compose.mlnffi.VulkanImageTarget

internal class MacVulkanContext private constructor(private val context: VulkanDevice) :
  AutoCloseable {
  val handles
    get() = context.handles

  fun device() = context.device

  fun physicalDevice() = context.physicalDevice

  fun waitIdle() = context.waitIdle()

  override fun close() = context.close()

  fun createImportedTexture(texture: NativeHandle, extent: MapExtent) =
    MacVulkanImportedTexture.create(this, texture, extent)

  companion object {
    fun create(requiredMetalDevice: Long) =
      MacVulkanContext(
        VulkanDevice.create(setOf(VK_EXT_METAL_OBJECTS_EXTENSION_NAME)) { _, device ->
          MemoryStack.stackPush().use { stack ->
            val info =
              VkExportMetalDeviceInfoEXT.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_EXPORT_METAL_DEVICE_INFO_EXT)
            val objects =
              VkExportMetalObjectsInfoEXT.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_EXPORT_METAL_OBJECTS_INFO_EXT)
                .pNext(info.address())
            vkExportMetalObjectsEXT(device, objects)
            info.mtlDevice() == requiredMetalDevice
          }
        }
      )
  }
}

internal class MacVulkanImportedTexture
private constructor(
  private val context: MacVulkanContext,
  private val metalTexture: NativeHandle,
  val extent: MapExtent,
) : AutoCloseable {
  private var image = NULL
  private var view = NULL

  fun target(generation: Long): VulkanImageTarget =
    VulkanImageTarget(
      context = context.handles,
      image = NativeHandle(image),
      imageView = NativeHandle(view),
      format = VK_FORMAT_B8G8R8A8_UNORM,
      initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
      finalLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
      queueFamilyIndex = context.handles.graphicsQueueFamilyIndex,
      extent = extent,
      generation = generation,
    )

  private fun create() {
    MemoryStack.stackPush().use { stack ->
      val importTexture =
        VkImportMetalTextureInfoEXT.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_IMPORT_METAL_TEXTURE_INFO_EXT)
          .plane(VK_IMAGE_ASPECT_COLOR_BIT)
          .mtlTexture(metalTexture.address)
      val imageInfo =
        VkImageCreateInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
          .pNext(importTexture.address())
          .imageType(VK_IMAGE_TYPE_2D)
          .format(VK_FORMAT_B8G8R8A8_UNORM)
          .extent(
            VkExtent3D.calloc(stack)
              .width(extent.physicalWidth)
              .height(extent.physicalHeight)
              .depth(1)
          )
          .mipLevels(1)
          .arrayLayers(1)
          .samples(VK_SAMPLE_COUNT_1_BIT)
          .tiling(VK_IMAGE_TILING_OPTIMAL)
          .usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or VK_IMAGE_USAGE_SAMPLED_BIT)
          .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
          .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
      val imageOut = stack.mallocLong(1)
      checkVulkan(vkCreateImage(context.device(), imageInfo, null, imageOut), "vkCreateImage")
      image = imageOut[0]

      val viewInfo =
        VkImageViewCreateInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
          .image(image)
          .viewType(VK_IMAGE_VIEW_TYPE_2D)
          .format(VK_FORMAT_B8G8R8A8_UNORM)
          .subresourceRange(
            VkImageSubresourceRange.calloc(stack)
              .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
              .baseMipLevel(0)
              .levelCount(1)
              .baseArrayLayer(0)
              .layerCount(1)
          )
      val viewOut = stack.mallocLong(1)
      checkVulkan(vkCreateImageView(context.device(), viewInfo, null, viewOut), "vkCreateImageView")
      view = viewOut[0]
    }
  }

  override fun close() {
    context.waitIdle()
    if (view != NULL) {
      vkDestroyImageView(context.device(), view, null)
      view = NULL
    }
    if (image != NULL) {
      vkDestroyImage(context.device(), image, null)
      image = NULL
    }
  }

  companion object {
    fun create(
      context: MacVulkanContext,
      metalTexture: NativeHandle,
      extent: MapExtent,
    ): MacVulkanImportedTexture {
      val texture = MacVulkanImportedTexture(context, metalTexture, extent)
      try {
        texture.create()
        return texture
      } catch (error: RuntimeException) {
        texture.close()
        throw error
      }
    }
  }
}
