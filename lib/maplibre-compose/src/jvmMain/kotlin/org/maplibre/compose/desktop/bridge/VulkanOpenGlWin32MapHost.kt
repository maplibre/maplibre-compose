package org.maplibre.compose.desktop.bridge

import androidx.compose.ui.graphics.drawscope.DrawScope
import org.lwjgl.opengl.GL11.GL_RGBA8
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME
import org.lwjgl.vulkan.KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME
import org.lwjgl.vulkan.KHRExternalMemoryWin32.VK_STRUCTURE_TYPE_IMPORT_MEMORY_WIN32_HANDLE_INFO_KHR
import org.lwjgl.vulkan.KHRExternalMemoryWin32.VK_STRUCTURE_TYPE_MEMORY_WIN32_HANDLE_PROPERTIES_KHR
import org.lwjgl.vulkan.KHRExternalMemoryWin32.vkGetMemoryWin32HandlePropertiesKHR
import org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR
import org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME
import org.lwjgl.vulkan.KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME
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
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO
import org.lwjgl.vulkan.VK10.vkAllocateMemory
import org.lwjgl.vulkan.VK10.vkBindImageMemory
import org.lwjgl.vulkan.VK10.vkCreateDevice
import org.lwjgl.vulkan.VK10.vkCreateImage
import org.lwjgl.vulkan.VK10.vkCreateImageView
import org.lwjgl.vulkan.VK10.vkCreateInstance
import org.lwjgl.vulkan.VK10.vkDestroyDevice
import org.lwjgl.vulkan.VK10.vkDestroyImage
import org.lwjgl.vulkan.VK10.vkDestroyImageView
import org.lwjgl.vulkan.VK10.vkDestroyInstance
import org.lwjgl.vulkan.VK10.vkDeviceWaitIdle
import org.lwjgl.vulkan.VK10.vkEnumeratePhysicalDevices
import org.lwjgl.vulkan.VK10.vkFreeMemory
import org.lwjgl.vulkan.VK10.vkGetDeviceQueue
import org.lwjgl.vulkan.VK10.vkGetImageMemoryRequirements
import org.lwjgl.vulkan.VK10.vkQueueWaitIdle
import org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1
import org.lwjgl.vulkan.VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D11_TEXTURE_BIT
import org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO
import org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO
import org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES
import org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2
import org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2
import org.lwjgl.vulkan.VkApplicationInfo
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkDeviceCreateInfo
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo
import org.lwjgl.vulkan.VkExtent3D
import org.lwjgl.vulkan.VkExternalMemoryImageCreateInfo
import org.lwjgl.vulkan.VkImageCreateInfo
import org.lwjgl.vulkan.VkImageSubresourceRange
import org.lwjgl.vulkan.VkImageViewCreateInfo
import org.lwjgl.vulkan.VkImportMemoryWin32HandleInfoKHR
import org.lwjgl.vulkan.VkInstance
import org.lwjgl.vulkan.VkInstanceCreateInfo
import org.lwjgl.vulkan.VkMemoryAllocateInfo
import org.lwjgl.vulkan.VkMemoryDedicatedAllocateInfo
import org.lwjgl.vulkan.VkMemoryRequirements
import org.lwjgl.vulkan.VkMemoryWin32HandlePropertiesKHR
import org.lwjgl.vulkan.VkPhysicalDevice
import org.lwjgl.vulkan.VkPhysicalDeviceIDProperties
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2
import org.lwjgl.vulkan.VkQueue
import org.maplibre.compose.desktop.ComposeMapHost
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.EglContextHandles
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiHostException
import org.maplibre.compose.mlnffi.MlnFfiMapFrame
import org.maplibre.compose.mlnffi.MlnFfiMapFrameAcquisition
import org.maplibre.compose.mlnffi.MlnFfiMapHost
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.compose.mlnffi.NativeHandle
import org.maplibre.compose.mlnffi.OpenGlTextureTarget
import org.maplibre.compose.mlnffi.RenderBackendPair
import org.maplibre.compose.mlnffi.TextureOrigin
import org.maplibre.compose.mlnffi.VulkanContextHandles
import org.maplibre.compose.mlnffi.VulkanImageTarget

/**
 * Bridges MapLibre's Vulkan rendering into Compose's ANGLE/GLES context on Windows.
 *
 * MapLibre draws into a D3D11 texture created on ANGLE's device. Vulkan imports the NT handle;
 * Compose samples the same texture via `EGL_ANGLE_d3d_texture_client_buffer`.
 */
internal class VulkanOpenGlWin32MapHost(private val gpuHost: ComposeMapHost) : MlnFfiMapHost {
  private val rendererThread = MapRendererThread("maplibre-windows-vulkan-gl-renderer")
  private val presenter = OpenGlPresenter()
  private val frameCompletion = ComposeFrameCompletion()
  private var vulkan: WindowsOpenGlVulkanContext? = null
  private var texture: WindowsOpenGlSharedTexture? = null
  private val retiredTextures = mutableMapOf<Long, WindowsOpenGlSharedTexture>()
  private var generation = 0L
  private var currentExtent = MapExtent.Empty

  override val backends: RenderBackendPair =
    RenderBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL)

  override fun acquireFrame(
    frameId: Long,
    extent: MapExtent,
    presentationTimeNanos: Long?,
  ): MlnFfiMapFrameAcquisition =
    gpuHost.withOpenGlContextOrNull { context ->
      frameCompletion.prepare(context.skiaContext, ::abandonContext)
      if (texture == null || extent != currentExtent) recreateTexture(extent)
      MlnFfiMapFrameAcquisition.Acquired(
        MlnFfiMapFrame(
          frameId = frameId,
          extent = extent,
          target =
            requireNotNull(texture) { "Windows OpenGL texture is not initialized" }
              .exported
              .target(generation),
          presentationTimeNanos = presentationTimeNanos,
        )
      )
    } ?: MlnFfiMapFrameAcquisition.NotReady

  override fun completeProducerAccess(frame: MlnFfiMapFrame) {
    rendererThread.run { vulkan?.waitIdle() }
  }

  override fun <T> withProducerAccess(frame: MlnFfiMapFrame, action: () -> T): T =
    rendererThread.run(action)

  override fun <T> withRendererAccess(action: () -> T): T = rendererThread.run(action)

  override fun enqueueRenderer(action: () -> Unit): Boolean = rendererThread.post(action)

  override fun draw(scope: DrawScope, target: MlnFfiRenderTarget): Boolean {
    if (target !is VulkanImageTarget) return false
    return gpuHost.withOpenGlContextOrNull { context ->
      frameCompletion.prepare(context.skiaContext, ::abandonContext)
      val sharedTexture =
        if (target.generation == generation) texture else retiredTextures[target.generation]
      val imported = sharedTexture?.imported ?: return@withOpenGlContextOrNull false
      val drew =
        presenter.draw(
          scope,
          context.skiaContext,
          imported.target(target.generation),
          frameCompletion,
        )
      if (drew) disposeRetiredTextures(exceptGeneration = target.generation)
      drew
    } ?: false
  }

  override fun close() {
    try {
      frameCompletion.abandon()
      runCatching {
        gpuHost.withOpenGlContext {
          disposeAllTextures()
          presenter.close()
        }
      }
    } finally {
      val closing = vulkan
      vulkan = null
      try {
        closing?.close()
      } finally {
        rendererThread.close()
      }
    }
  }

  private fun recreateTexture(extent: MapExtent) {
    if (extent.isEmpty) {
      disposeAllTextures()
      currentExtent = MapExtent.Empty
      generation += 1
      return
    }

    val angleDevice = AngleEgl.angleD3d11Device()
    val context =
      vulkan
        ?: rendererThread
          .run { WindowsOpenGlVulkanContext.create(WindowsD3D11Interop.adapterLuidOf(angleDevice)) }
          .also { vulkan = it }
    val d3d11 = WindowsD3D11Interop.createSharedTextureOnDevice(angleDevice, extent)
    try {
      val exported = rendererThread.run { context.importD3D11Texture(d3d11.sharedHandle, extent) }
      try {
        val imported = WindowsOpenGlImportedTexture.bindAngle(d3d11.texture, extent)
        texture?.let { retiredTextures[generation] = it }
        texture = WindowsOpenGlSharedTexture(d3d11, exported, imported)
        currentExtent = extent
        generation += 1
      } catch (error: RuntimeException) {
        exported.close()
        throw error
      }
    } catch (error: RuntimeException) {
      d3d11.close()
      throw error
    }
  }

  private fun abandonContext() {
    presenter.abandon()
    texture?.let { retiredTextures[generation] = it }
    texture = null
    retiredTextures.values.forEach(WindowsOpenGlSharedTexture::abandonImported)
    currentExtent = MapExtent.Empty
  }

  private fun disposeRetiredTextures(exceptGeneration: Long? = null) {
    val iterator = retiredTextures.iterator()
    while (iterator.hasNext()) {
      val entry = iterator.next()
      if (entry.key != exceptGeneration) {
        entry.value.close()
        iterator.remove()
      }
    }
  }

  private fun disposeAllTextures() {
    texture?.close()
    texture = null
    disposeRetiredTextures()
  }

  private inner class WindowsOpenGlSharedTexture(
    val d3d11: WindowsD3D11SharedTexture,
    val exported: WindowsOpenGlExportedVulkanTexture,
    val imported: WindowsOpenGlImportedTexture,
  ) : AutoCloseable {
    override fun close() {
      presenter.forget(imported.textureName)
      imported.close()
      exported.close()
      d3d11.close()
    }

    fun abandonImported() {
      imported.abandon()
    }
  }
}

/** The Vulkan instance, device, and queue MapLibre renders with on the Windows OpenGL path. */
internal class WindowsOpenGlVulkanContext
private constructor(private val preferredAdapterLuid: Long) : AutoCloseable {
  private var instance: VkInstance? = null
  private var physicalDevice: VkPhysicalDevice? = null
  private var device: VkDevice? = null
  private var graphicsQueue: VkQueue? = null
  private var graphicsQueueFamilyIndex = 0

  val handles: VulkanContextHandles
    get() =
      VulkanContextHandles(
        instance = NativeHandle(instance().address()),
        physicalDevice = NativeHandle(physicalDevice().address()),
        device = NativeHandle(device().address()),
        graphicsQueue = NativeHandle(graphicsQueue().address()),
        graphicsQueueFamilyIndex = graphicsQueueFamilyIndex,
        getInstanceProcAddr = NativeHandle(vulkanFunctionAddress("vkGetInstanceProcAddr")),
        getDeviceProcAddr = NativeHandle(vulkanFunctionAddress("vkGetDeviceProcAddr")),
      )

  fun importD3D11Texture(sharedHandle: Long, extent: MapExtent): WindowsOpenGlExportedVulkanTexture =
    WindowsOpenGlExportedVulkanTexture.create(this, sharedHandle, extent)

  fun waitIdle() {
    graphicsQueue?.let { checkVulkan(vkQueueWaitIdle(it), "vkQueueWaitIdle") }
      ?: device?.let { checkVulkan(vkDeviceWaitIdle(it), "vkDeviceWaitIdle") }
  }

  internal fun physicalDevice(): VkPhysicalDevice =
    checkNotNull(physicalDevice) { "Vulkan physical device is not initialized" }

  internal fun device(): VkDevice = checkNotNull(device) { "Vulkan device is not initialized" }

  private fun instance(): VkInstance =
    checkNotNull(instance) { "Vulkan instance is not initialized" }

  private fun graphicsQueue(): VkQueue =
    checkNotNull(graphicsQueue) { "Vulkan graphics queue is not initialized" }

  private fun createInstance() {
    ensureVulkanFunctionProvider()
    MemoryStack.stackPush().use { stack ->
      val available = stack.vulkanInstanceExtensions()
      val extensions = LinkedHashSet<String>()
      val enablePortability = VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME in available
      if (enablePortability) extensions.add(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME)
      if (VK_EXT_DEBUG_UTILS_EXTENSION_NAME in available) {
        extensions.add(VK_EXT_DEBUG_UTILS_EXTENSION_NAME)
      }
      val app =
        VkApplicationInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
          .pApplicationName(stack.UTF8("maplibre-compose"))
          .pEngineName(stack.UTF8("maplibre-native-ffi"))
          .apiVersion(VK_API_VERSION_1_1)
      val createInfo =
        VkInstanceCreateInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
          .pApplicationInfo(app)
          .ppEnabledExtensionNames(stack.vulkanStringBuffer(extensions))
      if (enablePortability) createInfo.flags(VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR)
      val out = stack.mallocPointer(1)
      checkVulkan(vkCreateInstance(createInfo, null, out), "vkCreateInstance")
      instance = VkInstance(out[0], createInfo)
    }
  }

  private fun pickPhysicalDeviceAndQueue() {
    MemoryStack.stackPush().use { stack ->
      val count = stack.mallocInt(1)
      checkVulkan(
        vkEnumeratePhysicalDevices(instance(), count, null),
        "vkEnumeratePhysicalDevices(count)",
      )
      check(count[0] != 0) { "No Vulkan physical devices found" }
      val devices = stack.mallocPointer(count[0])
      checkVulkan(
        vkEnumeratePhysicalDevices(instance(), count, devices),
        "vkEnumeratePhysicalDevices",
      )
      var fallback: Pair<VkPhysicalDevice, Int>? = null
      for (index in 0..<devices.capacity()) {
        val candidate = VkPhysicalDevice(devices[index], instance())
        if (VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME !in stack.vulkanDeviceExtensions(candidate)
        ) {
          continue
        }
        val queueFamily = stack.findVulkanGraphicsQueueFamily(candidate)
        if (queueFamily < 0) continue
        if (preferredAdapterLuid == 0L || deviceLuid(candidate) == preferredAdapterLuid) {
          physicalDevice = candidate
          graphicsQueueFamilyIndex = queueFamily
          return
        }
        if (fallback == null) fallback = candidate to queueFamily
      }
      fallback?.let { (candidate, queueFamily) ->
        physicalDevice = candidate
        graphicsQueueFamilyIndex = queueFamily
        return
      }
      throw MlnFfiHostException(
        "No Vulkan device supports graphics and $VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME"
      )
    }
  }

  private fun deviceLuid(candidate: VkPhysicalDevice): Long {
    MemoryStack.stackPush().use { stack ->
      val id =
        VkPhysicalDeviceIDProperties.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES)
      val properties =
        VkPhysicalDeviceProperties2.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2)
          .pNext(id.address())
      vkGetPhysicalDeviceProperties2(candidate, properties)
      return id.deviceLUID().getLong(0)
    }
  }

  private fun createDevice() {
    MemoryStack.stackPush().use { stack ->
      val deviceExtensions = stack.vulkanDeviceExtensions(physicalDevice())
      check(VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME in deviceExtensions) {
        "Selected Vulkan device does not support $VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME"
      }
      val extensions = LinkedHashSet<String>()
      extensions.add(VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME)
      if (VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME in deviceExtensions) {
        extensions.add(VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME)
      }
      val priorities = stack.floats(1.0f)
      val queueInfo =
        VkDeviceQueueCreateInfo.calloc(1, stack)
          .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
          .queueFamilyIndex(graphicsQueueFamilyIndex)
          .pQueuePriorities(priorities)
      val createInfo =
        VkDeviceCreateInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
          .pQueueCreateInfos(queueInfo)
          .ppEnabledExtensionNames(stack.vulkanStringBuffer(extensions))
      val out = stack.mallocPointer(1)
      checkVulkan(vkCreateDevice(physicalDevice(), createInfo, null, out), "vkCreateDevice")
      device = VkDevice(out[0], physicalDevice(), createInfo)
      val queueOut = stack.mallocPointer(1)
      vkGetDeviceQueue(device(), graphicsQueueFamilyIndex, 0, queueOut)
      graphicsQueue = VkQueue(queueOut[0], device())
    }
  }

  override fun close() {
    device?.let {
      vkDeviceWaitIdle(it)
      vkDestroyDevice(it, null)
      device = null
    }
    instance?.let {
      vkDestroyInstance(it, null)
      instance = null
    }
  }

  companion object {
    fun create(preferredAdapterLuid: Long = 0L): WindowsOpenGlVulkanContext {
      val context = WindowsOpenGlVulkanContext(preferredAdapterLuid)
      try {
        context.createInstance()
        context.pickPhysicalDeviceAndQueue()
        context.createDevice()
        return context
      } catch (error: RuntimeException) {
        context.close()
        throw error
      }
    }
  }
}

/** A `VkImage` whose memory is the imported D3D11 texture MapLibre renders into. */
internal class WindowsOpenGlExportedVulkanTexture
private constructor(
  private val context: WindowsOpenGlVulkanContext,
  private val sharedHandle: Long,
  private val extent: MapExtent,
) : AutoCloseable {
  private var image = NULL
  private var memory = NULL
  private var view = NULL

  fun target(generation: Long): VulkanImageTarget =
    VulkanImageTarget(
      context = context.handles,
      image = NativeHandle(image),
      imageView = NativeHandle(view),
      format = VK_FORMAT_R8G8B8A8_UNORM,
      initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
      finalLayout = VK_IMAGE_LAYOUT_GENERAL,
      queueFamilyIndex = context.handles.graphicsQueueFamilyIndex,
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
      checkVulkan(vkCreateImage(context.device(), imageInfo, null, imageOut), "vkCreateImage")
      image = imageOut[0]

      val requirements = VkMemoryRequirements.calloc(stack)
      vkGetImageMemoryRequirements(context.device(), image, requirements)
      val handleProperties =
        VkMemoryWin32HandlePropertiesKHR.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_MEMORY_WIN32_HANDLE_PROPERTIES_KHR)
      checkVulkan(
        vkGetMemoryWin32HandlePropertiesKHR(
          context.device(),
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
              context.physicalDevice(),
              requirements.memoryTypeBits() and handleProperties.memoryTypeBits(),
              "No compatible Vulkan memory type found for imported D3D11 texture",
            )
          )
      val memoryOut = stack.mallocLong(1)
      checkVulkan(
        vkAllocateMemory(context.device(), allocateInfo, null, memoryOut),
        "vkAllocateMemory",
      )
      memory = memoryOut[0]
      checkVulkan(vkBindImageMemory(context.device(), image, memory, 0), "vkBindImageMemory")

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
    if (memory != NULL) {
      vkFreeMemory(context.device(), memory, null)
      memory = NULL
    }
  }

  companion object {
    fun create(
      context: WindowsOpenGlVulkanContext,
      sharedHandle: Long,
      extent: MapExtent,
    ): WindowsOpenGlExportedVulkanTexture {
      val texture = WindowsOpenGlExportedVulkanTexture(context, sharedHandle, extent)
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
    binding?.close()
    abandon()
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
