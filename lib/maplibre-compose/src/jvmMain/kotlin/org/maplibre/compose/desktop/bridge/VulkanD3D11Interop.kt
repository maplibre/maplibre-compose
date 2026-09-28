package org.maplibre.compose.desktop.bridge

import org.lwjgl.opengl.GL11.GL_RGBA8
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.vulkan.KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME
import org.lwjgl.vulkan.KHRExternalMemoryWin32.VK_STRUCTURE_TYPE_IMPORT_MEMORY_WIN32_HANDLE_INFO_KHR
import org.lwjgl.vulkan.KHRExternalMemoryWin32.VK_STRUCTURE_TYPE_MEMORY_WIN32_HANDLE_PROPERTIES_KHR
import org.lwjgl.vulkan.KHRExternalMemoryWin32.vkGetMemoryWin32HandlePropertiesKHR
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM
import org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL
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
import org.lwjgl.vulkan.VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D11_TEXTURE_BIT
import org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO
import org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO
import org.lwjgl.vulkan.VkExtent3D
import org.lwjgl.vulkan.VkExternalMemoryImageCreateInfo
import org.lwjgl.vulkan.VkImageCreateInfo
import org.lwjgl.vulkan.VkImageSubresourceRange
import org.lwjgl.vulkan.VkImageViewCreateInfo
import org.lwjgl.vulkan.VkImportMemoryWin32HandleInfoKHR
import org.lwjgl.vulkan.VkMemoryAllocateInfo
import org.lwjgl.vulkan.VkMemoryDedicatedAllocateInfo
import org.lwjgl.vulkan.VkMemoryRequirements
import org.lwjgl.vulkan.VkMemoryWin32HandlePropertiesKHR
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.EglContextHandles
import org.maplibre.compose.mlnffi.NativeHandle
import org.maplibre.compose.mlnffi.OpenGlTextureTarget
import org.maplibre.compose.mlnffi.TextureOrigin
import org.maplibre.compose.mlnffi.VulkanImageTarget

/** A Vulkan device on the DXGI adapter [adapterLuid] names, able to import Win32 handles. */
internal fun VulkanDevice.Companion.forAdapter(adapterLuid: Long): VulkanDevice {
  require(adapterLuid != 0L) { "ANGLE adapter LUID is required" }
  return create(setOf(VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME)) { physical, _ ->
    vulkanDeviceLuid(physical) == adapterLuid
  }
}

internal class WindowsOpenGlExportedVulkanTexture
private constructor(
  private val vulkan: VulkanDevice,
  private val sharedHandle: Long,
  private val extent: MapExtent,
) : ImportedMapTexture {
  override val storageExtent
    get() = extent

  private var image = NULL
  private var memory = NULL
  private var view = NULL

  override fun target(generation: Long): VulkanImageTarget =
    VulkanImageTarget(
      context = vulkan.handles,
      image = NativeHandle(image),
      imageView = NativeHandle(view),
      format = VK_FORMAT_R8G8B8A8_UNORM,
      initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
      finalLayout = VK_IMAGE_LAYOUT_GENERAL,
      queueFamilyIndex = vulkan.handles.graphicsQueueFamilyIndex,
      extent = extent,
      generation = generation,
    )

  private fun create() {
    MemoryStack.stackPush().use { stack ->
      val externalImageInfo =
        VkExternalMemoryImageCreateInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO)
          .handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D11_TEXTURE_BIT)
      val imageInfo =
        VkImageCreateInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
          .pNext(externalImageInfo.address())
          .imageType(VK_IMAGE_TYPE_2D)
          .format(VK_FORMAT_R8G8B8A8_UNORM)
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

      val requirements = VkMemoryRequirements.calloc(stack)
      vkGetImageMemoryRequirements(vulkan.device, image, requirements)
      val handleProperties =
        VkMemoryWin32HandlePropertiesKHR.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_MEMORY_WIN32_HANDLE_PROPERTIES_KHR)
      checkVulkan(
        vkGetMemoryWin32HandlePropertiesKHR(
          vulkan.device,
          VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D11_TEXTURE_BIT,
          sharedHandle,
          handleProperties,
        ),
        "vkGetMemoryWin32HandlePropertiesKHR",
      )
      val dedicated =
        VkMemoryDedicatedAllocateInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO)
          .image(image)
      val importInfo =
        VkImportMemoryWin32HandleInfoKHR.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_IMPORT_MEMORY_WIN32_HANDLE_INFO_KHR)
          .handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D11_TEXTURE_BIT)
          .handle(sharedHandle)
          .pNext(dedicated.address())
      val allocateInfo =
        VkMemoryAllocateInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
          .pNext(importInfo.address())
          .allocationSize(requirements.size())
          .memoryTypeIndex(
            findVulkanDeviceLocalMemoryType(
              vulkan.physicalDevice,
              requirements.memoryTypeBits() and handleProperties.memoryTypeBits(),
              "No compatible Vulkan memory type found for imported D3D11 texture",
            )
          )
      val memoryOut = stack.mallocLong(1)
      checkVulkan(
        vkAllocateMemory(vulkan.device, allocateInfo, null, memoryOut),
        "vkAllocateMemory",
      )
      memory = memoryOut[0]
      checkVulkan(vkBindImageMemory(vulkan.device, image, memory, 0), "vkBindImageMemory")

      val viewInfo =
        VkImageViewCreateInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
          .image(image)
          .viewType(VK_IMAGE_VIEW_TYPE_2D)
          .format(VK_FORMAT_R8G8B8A8_UNORM)
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
    fun create(
      vulkan: VulkanDevice,
      sharedHandle: Long,
      extent: MapExtent,
    ): WindowsOpenGlExportedVulkanTexture {
      val texture = WindowsOpenGlExportedVulkanTexture(vulkan, sharedHandle, extent)
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

/** Compose's GL texture: ANGLE's pbuffer wrapping the same D3D11 allocation. */
internal class WindowsOpenGlImportedTexture
private constructor(private val extent: MapExtent, private var binding: AngleBoundD3dTexture?) :
  AutoCloseable {
  val textureName: Int
    get() = binding?.textureName ?: 0

  fun target(generation: Long): OpenGlTextureTarget =
    OpenGlTextureTarget(
      context =
        EglContextHandles(NativeHandle(0), NativeHandle(0), NativeHandle(0), NativeHandle(0)),
      textureName = textureName,
      textureTarget = GL_TEXTURE_2D,
      format = GL_RGBA8,
      origin = TextureOrigin.TOP_LEFT,
      makeContextCurrent = {},
      extent = extent,
      generation = generation,
    )

  override fun close() {
    val closing = binding ?: return
    binding = null
    closing.close()
  }

  fun abandon() {
    binding = null
  }

  companion object {
    fun bindAngle(d3dTexture: Long, extent: MapExtent): WindowsOpenGlImportedTexture {
      check(AngleGl.isUsable()) { "Compose's ANGLE context has no usable GLES entry points" }
      return WindowsOpenGlImportedTexture(extent, AngleEgl.bindD3dTexture(d3dTexture))
    }
  }
}
