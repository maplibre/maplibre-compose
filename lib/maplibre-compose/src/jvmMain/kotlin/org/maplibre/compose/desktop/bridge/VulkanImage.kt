package org.maplibre.compose.desktop.bridge

import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.vulkan.EXTMetalObjects.VK_STRUCTURE_TYPE_IMPORT_METAL_TEXTURE_INFO_EXT
import org.lwjgl.vulkan.KHRExternalMemoryWin32.VK_STRUCTURE_TYPE_IMPORT_MEMORY_WIN32_HANDLE_INFO_KHR
import org.lwjgl.vulkan.KHRExternalMemoryWin32.VK_STRUCTURE_TYPE_MEMORY_WIN32_HANDLE_PROPERTIES_KHR
import org.lwjgl.vulkan.KHRExternalMemoryWin32.vkGetMemoryWin32HandlePropertiesKHR
import org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT
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
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO
import org.lwjgl.vulkan.VK10.vkAllocateMemory
import org.lwjgl.vulkan.VK10.vkBindImageMemory
import org.lwjgl.vulkan.VK10.vkCreateImage
import org.lwjgl.vulkan.VK10.vkCreateImageView
import org.lwjgl.vulkan.VK10.vkDestroyImage
import org.lwjgl.vulkan.VK10.vkDestroyImageView
import org.lwjgl.vulkan.VK10.vkFreeMemory
import org.lwjgl.vulkan.VK10.vkGetImageMemoryRequirements
import org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO
import org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO
import org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO
import org.lwjgl.vulkan.VkExportMemoryAllocateInfo
import org.lwjgl.vulkan.VkExtent3D
import org.lwjgl.vulkan.VkExternalMemoryImageCreateInfo
import org.lwjgl.vulkan.VkImageCreateInfo
import org.lwjgl.vulkan.VkImageSubresourceRange
import org.lwjgl.vulkan.VkImageViewCreateInfo
import org.lwjgl.vulkan.VkImportMemoryWin32HandleInfoKHR
import org.lwjgl.vulkan.VkImportMetalTextureInfoEXT
import org.lwjgl.vulkan.VkMemoryAllocateInfo
import org.lwjgl.vulkan.VkMemoryDedicatedAllocateInfo
import org.lwjgl.vulkan.VkMemoryRequirements
import org.lwjgl.vulkan.VkMemoryWin32HandlePropertiesKHR
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.NativeHandle
import org.maplibre.compose.mlnffi.VulkanImageTarget

/** Where a [VulkanImage]'s memory comes from. */
internal sealed interface VulkanImageMemory {
  /** A dedicated allocation the image owns, exportable as a handle of [handleType]. */
  data class Exported(val handleType: Int) : VulkanImageMemory

  /**
   * A dedicated allocation imported from [handle], a Win32 handle of [handleType]. A Direct3D
   * handle names a whole resource rather than a suballocatable heap, so the import must be a
   * dedicated allocation bound to exactly this image. [resource] names it in errors.
   */
  data class ImportedWin32(val handleType: Int, val handle: Long, val resource: String) :
    VulkanImageMemory

  /** The storage of an `MTLTexture`, which the image aliases without memory of its own. */
  data class MetalTexture(val texture: NativeHandle) : VulkanImageMemory
}

/**
 * A single-sampled 2D color `VkImage` MapLibre renders into, with its view and any memory it owns.
 * The Windows and macOS hosts access and dispose it on the renderer thread; the Linux host, which
 * exports its memory to Compose, creates and closes it on Compose's GPU thread.
 */
internal class VulkanImage
private constructor(
  val vulkan: VulkanDevice,
  private val extent: MapExtent,
  private val format: Int,
  private val finalLayout: Int,
) : ImportedMapTexture {
  private var image = NULL
  private var view = NULL

  /** The `VkDeviceMemory` bound to the image, or [NULL] when it aliases a Metal texture. */
  var memory = NULL
    private set

  /** The size of [memory] in bytes. */
  var memorySize = 0L
    private set

  override fun target(generation: Long): VulkanImageTarget =
    VulkanImageTarget(
      context = vulkan.handles,
      image = NativeHandle(image),
      imageView = NativeHandle(view),
      format = format,
      initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
      finalLayout = finalLayout,
      extent = extent,
      generation = generation,
    )

  private fun create(source: VulkanImageMemory) {
    MemoryStack.stackPush().use { stack ->
      val imageNext =
        when (source) {
          is VulkanImageMemory.Exported -> externalMemoryImageInfo(stack, source.handleType)
          is VulkanImageMemory.ImportedWin32 -> externalMemoryImageInfo(stack, source.handleType)
          is VulkanImageMemory.MetalTexture ->
            VkImportMetalTextureInfoEXT.calloc(stack)
              .sType(VK_STRUCTURE_TYPE_IMPORT_METAL_TEXTURE_INFO_EXT)
              .plane(VK_IMAGE_ASPECT_COLOR_BIT)
              .mtlTexture(source.texture.address)
              .address()
        }
      val imageInfo =
        VkImageCreateInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
          .pNext(imageNext)
          .imageType(VK_IMAGE_TYPE_2D)
          .format(format)
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
      checkVulkan(vkCreateImage(vulkan.device, imageInfo, null, imageOut), "vkCreateImage")
      image = imageOut[0]

      if (source !is VulkanImageMemory.MetalTexture) allocateMemory(stack, source)

      val viewInfo =
        VkImageViewCreateInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
          .image(image)
          .viewType(VK_IMAGE_VIEW_TYPE_2D)
          .format(format)
          .subresourceRange(
            VkImageSubresourceRange.calloc(stack)
              .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
              .baseMipLevel(0)
              .levelCount(1)
              .baseArrayLayer(0)
              .layerCount(1)
          )
      val viewOut = stack.mallocLong(1)
      checkVulkan(vkCreateImageView(vulkan.device, viewInfo, null, viewOut), "vkCreateImageView")
      view = viewOut[0]
    }
  }

  private fun allocateMemory(stack: MemoryStack, source: VulkanImageMemory) {
    val requirements = VkMemoryRequirements.calloc(stack)
    vkGetImageMemoryRequirements(vulkan.device, image, requirements)
    memorySize = requirements.size()
    val dedicated =
      VkMemoryDedicatedAllocateInfo.calloc(stack)
        .sType(VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO)
        .image(image)
    val allocateNext: Long
    val memoryTypeBits: Int
    val noMemoryType: String
    when (source) {
      is VulkanImageMemory.Exported -> {
        allocateNext =
          VkExportMemoryAllocateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO)
            .handleTypes(source.handleType)
            .pNext(dedicated.address())
            .address()
        memoryTypeBits = requirements.memoryTypeBits()
        noMemoryType = "No compatible Vulkan memory type found"
      }
      is VulkanImageMemory.ImportedWin32 -> {
        val handleProperties =
          VkMemoryWin32HandlePropertiesKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_MEMORY_WIN32_HANDLE_PROPERTIES_KHR)
        checkVulkan(
          vkGetMemoryWin32HandlePropertiesKHR(
            vulkan.device,
            source.handleType,
            source.handle,
            handleProperties,
          ),
          "vkGetMemoryWin32HandlePropertiesKHR",
        )
        allocateNext =
          VkImportMemoryWin32HandleInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_IMPORT_MEMORY_WIN32_HANDLE_INFO_KHR)
            .handleType(source.handleType)
            .handle(source.handle)
            .pNext(dedicated.address())
            .address()
        memoryTypeBits = requirements.memoryTypeBits() and handleProperties.memoryTypeBits()
        noMemoryType = "No compatible Vulkan memory type found for imported ${source.resource}"
      }
      is VulkanImageMemory.MetalTexture ->
        error("A Metal texture's image has no memory to allocate")
    }
    val allocateInfo =
      VkMemoryAllocateInfo.calloc(stack)
        .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
        .pNext(allocateNext)
        .allocationSize(requirements.size())
        .memoryTypeIndex(
          findVulkanDeviceLocalMemoryType(vulkan.physicalDevice, memoryTypeBits, noMemoryType)
        )
    val memoryOut = stack.mallocLong(1)
    checkVulkan(vkAllocateMemory(vulkan.device, allocateInfo, null, memoryOut), "vkAllocateMemory")
    memory = memoryOut[0]
    checkVulkan(vkBindImageMemory(vulkan.device, image, memory, 0), "vkBindImageMemory")
  }

  override fun close() {
    vulkan.waitIdle()
    if (view != NULL) {
      vkDestroyImageView(vulkan.device, view, null)
      view = NULL
    }
    if (image != NULL) {
      vkDestroyImage(vulkan.device, image, null)
      image = NULL
    }
    if (memory != NULL) {
      vkFreeMemory(vulkan.device, memory, null)
      memory = NULL
    }
  }

  companion object {
    /** Creates an image of [extent] in [format]; MapLibre leaves it in [finalLayout]. */
    fun create(
      vulkan: VulkanDevice,
      extent: MapExtent,
      format: Int,
      finalLayout: Int,
      memory: VulkanImageMemory,
    ): VulkanImage {
      val image = VulkanImage(vulkan, extent, format, finalLayout)
      try {
        image.create(memory)
        return image
      } catch (error: RuntimeException) {
        image.close()
        throw error
      }
    }

    private fun externalMemoryImageInfo(stack: MemoryStack, handleType: Int): Long =
      VkExternalMemoryImageCreateInfo.calloc(stack)
        .sType(VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO)
        .handleTypes(handleType)
        .address()
  }
}
