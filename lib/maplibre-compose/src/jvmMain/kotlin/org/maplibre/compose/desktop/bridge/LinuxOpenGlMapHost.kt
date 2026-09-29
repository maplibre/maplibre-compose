package org.maplibre.compose.desktop.bridge

import androidx.compose.ui.graphics.drawscope.DrawScope
import org.lwjgl.opengl.EXTMemoryObject.GL_DEDICATED_MEMORY_OBJECT_EXT
import org.lwjgl.opengl.EXTMemoryObject.GL_DEVICE_UUID_EXT
import org.lwjgl.opengl.EXTMemoryObject.GL_NUM_DEVICE_UUIDS_EXT
import org.lwjgl.opengl.EXTMemoryObject.GL_OPTIMAL_TILING_EXT
import org.lwjgl.opengl.EXTMemoryObject.GL_TEXTURE_TILING_EXT
import org.lwjgl.opengl.EXTMemoryObject.GL_UUID_SIZE_EXT
import org.lwjgl.opengl.EXTMemoryObject.glCreateMemoryObjectsEXT
import org.lwjgl.opengl.EXTMemoryObject.glDeleteMemoryObjectsEXT
import org.lwjgl.opengl.EXTMemoryObject.glGetUnsignedBytei_vEXT
import org.lwjgl.opengl.EXTMemoryObject.glMemoryObjectParameteriEXT
import org.lwjgl.opengl.EXTMemoryObject.glTexStorageMem2DEXT
import org.lwjgl.opengl.EXTMemoryObjectFD.GL_HANDLE_TYPE_OPAQUE_FD_EXT
import org.lwjgl.opengl.EXTMemoryObjectFD.glImportMemoryFdEXT
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
import org.lwjgl.opengl.GL11.glGetInteger
import org.lwjgl.opengl.GL11.glTexParameteri
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.linux.UNISTD
import org.lwjgl.vulkan.KHRExternalMemoryFd.VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME
import org.lwjgl.vulkan.KHRExternalMemoryFd.vkGetMemoryFdKHR
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL
import org.lwjgl.vulkan.VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT
import org.lwjgl.vulkan.VkMemoryGetFdInfoKHR
import org.maplibre.compose.desktop.ComposeMapPresentationHost
import org.maplibre.compose.desktop.OpenGlComposeGpuContext
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.EglContextHandles
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiMapDestination
import org.maplibre.compose.mlnffi.MlnFfiMapFrame
import org.maplibre.compose.mlnffi.MlnFfiMapFrameAcquisition
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.compose.mlnffi.NativeHandle
import org.maplibre.compose.mlnffi.OpenGlTextureTarget
import org.maplibre.compose.mlnffi.RenderBackendPair
import org.maplibre.compose.mlnffi.TextureOrigin

private const val VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR = 1000074002

/** Shares Linux external memory between a Vulkan or EGL map producer and Compose OpenGL. */
internal class LinuxOpenGlMapHost(
  presentationHost: ComposeMapPresentationHost,
  producer: MapRenderBackend = MapRenderBackend.VULKAN,
) :
  SharedTextureMapHost<OpenGlComposeGpuContext, LinuxOpenGlMapHost.LinuxSharedTexture>(
    presentationHost,
    RenderBackendPair(producer, ComposeRenderBackend.OPENGL),
    "maplibre-linux-map-renderer",
  ) {
  private val presenter = SkiaTexturePresenter(OpenGlTextureWrapper.Native)
  private var vulkan: VulkanDevice? = null
  private var egl: DesktopEglContext? = null

  @Volatile private var acquireProducerWrites = false

  // Importing into GL needs Compose's context current, so reallocation happens in acquireFrame.
  // resize() can run on the renderer thread while the GPU thread waits for it and cannot provide
  // that context.

  override fun acquireFrame(extent: MapExtent): MlnFfiMapFrameAcquisition =
    withPreparedContext {
      if (textures.current?.extent != extent) recreateTexture(extent)
      MlnFfiMapFrameAcquisition.Acquired(
        MlnFfiMapFrame(
          target =
            requireNotNull(textures.current) { "Map texture is not initialized" }
              .target(textures.generation)
        )
      )
    } ?: MlnFfiMapFrameAcquisition.NotReady

  override fun waitForProducers() {
    if (producer == MapRenderBackend.OPENGL) egl?.waitIdle() else vulkan?.waitIdle()
  }

  override fun completeProducerAccess(frame: MlnFfiMapFrame) {
    super.completeProducerAccess(frame)
    acquireProducerWrites = true
  }

  override fun present(
    scope: DrawScope,
    context: OpenGlComposeGpuContext,
    texture: LinuxSharedTexture,
    generation: Long,
    destination: MlnFfiMapDestination,
  ): Boolean {
    if (acquireProducerWrites) {
      // EXT_memory_object does not make producer completion visible to this context. glFinish
      // acquires those writes.
      glFinish()
      acquireProducerWrites = false
    }
    return presenter.draw(
      scope,
      context.skiaContext,
      texture.imported.target(generation),
      destination,
      frameCompletion,
    )
  }

  /** Frees every view of [texture]'s allocation. Compose's GL context must be current. */
  override fun release(texture: LinuxSharedTexture) {
    texture.close()
  }

  override fun <R> withComposeContext(action: (OpenGlComposeGpuContext) -> R): R? =
    presentationHost.withOpenGlContextOrNull(action)

  /** Drops OpenGL names that cannot be used or deleted in the replacement context. */
  override fun contextReplaced() {
    presenter.abandonAll()
    acquireProducerWrites = false
    // Keep the Vulkan allocation and device alive: MapLibre's render session still refers to both
    // until the next producer frame retargets it.
    textures.retireCurrent()
    textures.all.forEach(LinuxSharedTexture::abandonImported)
  }

  override fun closeTextures() {
    // At window close the Compose surface may already be gone; the driver reclaims the GL objects
    // along with the context.
    runCatching {
      presentationHost.withOpenGlContext {
        textures.releaseAll()
        presenter.closeAll()
      }
    }
      .onFailure {
        contextReplaced()
        textures.releaseAll()
      }
  }

  override fun closeProducers() {
    val closing = vulkan
    vulkan = null
    egl?.close()
    closing?.close()
  }

  private fun recreateTexture(extent: MapExtent) {
    if (extent.isEmpty) {
      textures.releaseAll()
      textures.replaceCurrent(null)
      return
    }

    val context =
      vulkan ?: VulkanDevice.forOpenGlDevices(currentOpenGlDeviceUuids()).also { vulkan = it }
    val producerContext =
      if (producer == MapRenderBackend.OPENGL) {
        egl
          ?: run {
            val deviceUuid = vulkanDeviceUuid(context.physicalDevice)
            rendererThread.run {
              DesktopEglContext.create(requiredDeviceUuids = setOf(deviceUuid))
            }
          }
            .also { egl = it }
      } else null
    val newExported = context.createExportableImage(extent)
    var producerImport: LinuxOpenGlImportedTexture? = null
    try {
      if (producerContext != null) {
        producerImport = rendererThread.run {
          producerContext.makeCurrent()
          LinuxOpenGlImportedTexture.create(
            newExported.exportFd(),
            newExported.memorySize,
            extent,
            TextureOrigin.BOTTOM_LEFT,
          )
        }
      }
      val newImported =
        LinuxOpenGlImportedTexture.create(
          newExported.exportFd(),
          newExported.memorySize,
          extent,
          if (producerContext != null) TextureOrigin.BOTTOM_LEFT else TextureOrigin.TOP_LEFT,
        )
      textures.replaceCurrent(LinuxSharedTexture(extent, newExported, newImported, producerImport))
    } catch (error: RuntimeException) {
      rendererThread.run {
        producerContext?.makeCurrent()
        producerImport?.close()
      }
      newExported.close()
      throw error
    }
  }

  internal inner class LinuxSharedTexture(
    val extent: MapExtent,
    val exported: VulkanImage,
    val imported: LinuxOpenGlImportedTexture,
    val producerImport: LinuxOpenGlImportedTexture?,
  ) : AutoCloseable {
    fun target(generation: Long): MlnFfiRenderTarget =
      producerImport
        ?.target(generation)
        ?.copy(
          context = checkNotNull(egl).handles,
          makeContextCurrent = { checkNotNull(egl).makeCurrent() },
        ) ?: exported.target(generation)

    override fun close() {
      rendererThread.run {
        if (producerImport != null) {
          egl?.makeCurrent()
          producerImport.close()
        }
      }
      // Skia holds a surface wrapping this texture; it must be dropped before the texture is.
      presenter.forget(imported.textureName.toLong())
      imported.close()
      exported.close()
    }

    fun abandonImported() {
      imported.abandon()
    }
  }
}

/**
 * The device UUIDs Compose's OpenGL context can import memory from. Vulkan and OpenGL must be on
 * the same physical device for the export/import to work.
 */
internal fun currentOpenGlDeviceUuids(): Set<String> {
  val capabilities = ensureCapabilities()
  if (!capabilities.GL_EXT_memory_object) return emptySet()
  val count = glGetInteger(GL_NUM_DEVICE_UUIDS_EXT)
  if (count <= 0) return emptySet()
  MemoryStack.stackPush().use { stack ->
    return (0..<count).mapTo(linkedSetOf()) { index ->
      val uuid = stack.malloc(GL_UUID_SIZE_EXT)
      glGetUnsignedBytei_vEXT(GL_DEVICE_UUID_EXT, index, uuid)
      uuid.toUuidHex(GL_UUID_SIZE_EXT)
    }
  }
}

/**
 * A Vulkan device that can export memory as file descriptors, on one of [deviceUuids] when any are
 * given: Vulkan and OpenGL must be on the same physical device for the export/import to work.
 */
internal fun VulkanDevice.Companion.forOpenGlDevices(deviceUuids: Set<String>): VulkanDevice =
  create(setOf(VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME)) { physical, _ ->
    deviceUuids.isEmpty() || vulkanDeviceUuid(physical) in deviceUuids
  }

/** A `VkImage` of [extent] whose memory is exportable to OpenGL as a file descriptor. */
internal fun VulkanDevice.createExportableImage(extent: MapExtent) =
  VulkanImage.create(
    this,
    extent,
    VK_FORMAT_R8G8B8A8_UNORM,
    VK_IMAGE_LAYOUT_GENERAL,
    VulkanImageMemory.Exported(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT),
  )

/**
 * Exports the image memory as a file descriptor. Ownership transfers to the caller: importing it
 * into GL consumes it, and a failed import must close it.
 */
internal fun VulkanImage.exportFd(): Int {
  MemoryStack.stackPush().use { stack ->
    val fdInfo =
      VkMemoryGetFdInfoKHR.calloc(stack)
        .sType(VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR)
        .memory(memory)
        .handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT)
    val fdOut = stack.mallocInt(1)
    checkVulkan(vkGetMemoryFdKHR(vulkan.device, fdInfo, fdOut), "vkGetMemoryFdKHR")
    return fdOut[0]
  }
}

/** Compose's view of the same allocation, imported into its GL context as a texture. */
internal class LinuxOpenGlImportedTexture
private constructor(
  private val fd: Int,
  private val memorySize: Long,
  private val extent: MapExtent,
  private val origin: TextureOrigin,
) : AutoCloseable {
  private var memoryObject = 0

  /** The GL name of the imported texture. */
  var textureName: Int = 0
    private set

  /**
   * For presenting only: the context handles are zero and the make-current hook is a no-op, since
   * Compose already owns this GL context.
   */
  fun target(generation: Long): OpenGlTextureTarget =
    OpenGlTextureTarget(
      context =
        EglContextHandles(NativeHandle(0), NativeHandle(0), NativeHandle(0), NativeHandle(0)),
      textureName = textureName,
      textureTarget = GL_TEXTURE_2D,
      format = GL_RGBA8,
      origin = origin,
      makeContextCurrent = {},
      extent = extent,
      generation = generation,
    )

  private fun create() {
    var importedFd = false
    try {
      val capabilities = ensureCapabilities()
      check(capabilities.GL_EXT_memory_object) {
        "Compose's OpenGL context does not expose GL_EXT_memory_object, which is required to " +
          "import MapLibre's Vulkan image"
      }
      check(capabilities.GL_EXT_memory_object_fd) {
        "Compose's OpenGL context does not expose GL_EXT_memory_object_fd, which is required to " +
          "import MapLibre's Vulkan image"
      }

      // Compose and the bridge share this context, whose error flag is sticky. Establish ownership
      // of every error reported below before checking any of our own calls.
      clearGlErrors()
      memoryObject = glCreateMemoryObjectsEXT()
      glMemoryObjectParameteriEXT(memoryObject, GL_DEDICATED_MEMORY_OBJECT_EXT, GL_TRUE)
      glImportMemoryFdEXT(memoryObject, memorySize, GL_HANDLE_TYPE_OPAQUE_FD_EXT, fd)
      checkGl("glImportMemoryFdEXT")
      // The import took ownership of the descriptor; closing it now would be a double close.
      importedFd = true

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
        extent.physicalWidth,
        extent.physicalHeight,
        memoryObject,
        0,
      )
      glBindTexture(GL_TEXTURE_2D, 0)
      checkGl("glTexStorageMem2DEXT")
    } catch (error: RuntimeException) {
      if (!importedFd) closeFd(fd)
      throw error
    }
  }

  override fun close() {
    if (textureName == 0 && memoryObject == 0) return
    runCatching {
      ensureCapabilities()
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
  }

  /** Forgets names allocated by a lost context, which the replacement context must not delete. */
  fun abandon() {
    textureName = 0
    memoryObject = 0
  }

  companion object {
    fun create(
      fd: Int,
      memorySize: Long,
      extent: MapExtent,
      origin: TextureOrigin = TextureOrigin.TOP_LEFT,
    ): LinuxOpenGlImportedTexture {
      val imported = LinuxOpenGlImportedTexture(fd, memorySize, extent, origin)
      try {
        imported.create()
        return imported
      } catch (error: RuntimeException) {
        imported.close()
        throw error
      }
    }
  }
}

internal fun closeFd(fd: Int) {
  if (fd >= 0) runCatching { UNISTD.close(null, fd) }
}
