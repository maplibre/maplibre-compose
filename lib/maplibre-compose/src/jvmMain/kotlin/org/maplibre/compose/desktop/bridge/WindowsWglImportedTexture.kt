package org.maplibre.compose.desktop.bridge

import org.lwjgl.opengl.EXTMemoryObject.GL_DEDICATED_MEMORY_OBJECT_EXT
import org.lwjgl.opengl.EXTMemoryObject.GL_OPTIMAL_TILING_EXT
import org.lwjgl.opengl.EXTMemoryObject.GL_TEXTURE_TILING_EXT
import org.lwjgl.opengl.EXTMemoryObject.glCreateMemoryObjectsEXT
import org.lwjgl.opengl.EXTMemoryObject.glDeleteMemoryObjectsEXT
import org.lwjgl.opengl.EXTMemoryObject.glMemoryObjectParameteriEXT
import org.lwjgl.opengl.EXTMemoryObject.glTexStorageMem2DEXT
import org.lwjgl.opengl.EXTMemoryObjectWin32.GL_HANDLE_TYPE_D3D11_IMAGE_EXT
import org.lwjgl.opengl.EXTMemoryObjectWin32.GL_HANDLE_TYPE_D3D12_RESOURCE_EXT
import org.lwjgl.opengl.EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT
import org.lwjgl.opengl.GL11.GL_LINEAR
import org.lwjgl.opengl.GL11.GL_RGBA8
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T
import org.lwjgl.opengl.GL11.GL_TRUE
import org.lwjgl.opengl.GL11.glBindTexture
import org.lwjgl.opengl.GL11.glDeleteTextures
import org.lwjgl.opengl.GL11.glFinish
import org.lwjgl.opengl.GL11.glGenTextures
import org.lwjgl.opengl.GL11.glTexParameteri
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.OpenGlTextureTarget
import org.maplibre.compose.mlnffi.TextureOrigin

internal class WindowsWglImportedTexture
private constructor(
  private val context: WindowsWglContext,
  private val sharedHandle: Long,
  override val storageExtent: MapExtent,
  private val d3d11: Boolean,
) : ImportedMapTexture {
  private var memoryObject = 0
  private var textureName = 0

  override fun target(generation: Long): OpenGlTextureTarget =
    OpenGlTextureTarget(
      context = context.handles,
      textureName = textureName,
      textureTarget = GL_TEXTURE_2D,
      format = GL_RGBA8,
      origin = TextureOrigin.BOTTOM_LEFT,
      makeContextCurrent = { context.makeCurrent() },
      extent = storageExtent,
      generation = generation,
    )

  private fun create() {
    context.makeCurrent()
    val capabilities = ensureCapabilities()
    check(capabilities.GL_EXT_memory_object) {
      "Windows WGL context does not expose GL_EXT_memory_object"
    }
    check(capabilities.GL_EXT_memory_object_win32) {
      "Windows WGL context does not expose GL_EXT_memory_object_win32"
    }

    clearGlErrors()
    memoryObject = glCreateMemoryObjectsEXT()
    glMemoryObjectParameteriEXT(memoryObject, GL_DEDICATED_MEMORY_OBJECT_EXT, GL_TRUE)
    glImportMemoryWin32HandleEXT(
      memoryObject,
      0L,
      if (d3d11) GL_HANDLE_TYPE_D3D11_IMAGE_EXT else GL_HANDLE_TYPE_D3D12_RESOURCE_EXT,
      sharedHandle,
    )
    checkGl("glImportMemoryWin32HandleEXT")

    textureName = glGenTextures()
    glBindTexture(GL_TEXTURE_2D, textureName)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_TILING_EXT, GL_OPTIMAL_TILING_EXT)
    glTexStorageMem2DEXT(
      GL_TEXTURE_2D,
      1,
      GL_RGBA8,
      storageExtent.physicalWidth,
      storageExtent.physicalHeight,
      memoryObject,
      0,
    )
    glBindTexture(GL_TEXTURE_2D, 0)
    checkGl("glTexStorageMem2DEXT")
  }

  override fun close() {
    context.makeCurrent()
    glFinish()
    if (textureName != 0) {
      glDeleteTextures(textureName)
      textureName = 0
    }
    if (memoryObject != 0) {
      glDeleteMemoryObjectsEXT(memoryObject)
      memoryObject = 0
    }
  }

  companion object {
    fun create(
      context: WindowsWglContext,
      sharedHandle: Long,
      extent: MapExtent,
      d3d11: Boolean = false,
    ): WindowsWglImportedTexture {
      val texture = WindowsWglImportedTexture(context, sharedHandle, extent, d3d11)
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
