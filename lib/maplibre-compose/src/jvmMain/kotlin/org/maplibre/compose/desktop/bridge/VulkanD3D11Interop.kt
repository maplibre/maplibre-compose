package org.maplibre.compose.desktop.bridge

import org.lwjgl.opengl.GL11.GL_RGBA8
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.vulkan.KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL
import org.lwjgl.vulkan.VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D11_TEXTURE_BIT
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.EglContextHandles
import org.maplibre.compose.mlnffi.NativeHandle
import org.maplibre.compose.mlnffi.OpenGlTextureTarget
import org.maplibre.compose.mlnffi.TextureOrigin

/** A Vulkan device on the DXGI adapter [adapterLuid] names, able to import Win32 handles. */
internal fun VulkanDevice.Companion.forAdapter(adapterLuid: Long): VulkanDevice {
  require(adapterLuid != 0L) { "ANGLE adapter LUID is required" }
  return create(setOf(VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME)) { physical, _ ->
    vulkanDeviceLuid(physical) == adapterLuid
  }
}

/** A `VkImage` bound to the D3D11 texture [sharedHandle] names, allocated at [extent]. */
internal fun VulkanDevice.importD3D11Texture(sharedHandle: Long, extent: MapExtent) =
  VulkanImage.create(
    this,
    extent,
    VK_FORMAT_R8G8B8A8_UNORM,
    VK_IMAGE_LAYOUT_GENERAL,
    VulkanImageMemory.ImportedWin32(
      VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D11_TEXTURE_BIT,
      sharedHandle,
      "D3D11 texture",
    ),
  )

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
