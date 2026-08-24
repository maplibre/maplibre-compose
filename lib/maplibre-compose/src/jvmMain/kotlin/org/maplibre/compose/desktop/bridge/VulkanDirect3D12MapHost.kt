package org.maplibre.compose.desktop.bridge

import androidx.compose.ui.graphics.drawscope.DrawScope
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
import org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM
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
import org.lwjgl.vulkan.VK10.VK_SUCCESS
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
import org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1
import org.lwjgl.vulkan.VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_RESOURCE_BIT
import org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO
import org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO
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
import org.lwjgl.vulkan.VkQueue
import org.maplibre.compose.desktop.ComposeMapHost
import org.maplibre.compose.desktop.Direct3D12ComposeGpuContext
import org.maplibre.compose.desktop.onGpuThread
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiHostException
import org.maplibre.compose.mlnffi.MlnFfiMapFrame
import org.maplibre.compose.mlnffi.MlnFfiMapFrameAcquisition
import org.maplibre.compose.mlnffi.MlnFfiMapHost
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.compose.mlnffi.NativeHandle
import org.maplibre.compose.mlnffi.RenderBackendPair
import org.maplibre.compose.mlnffi.VulkanContextHandles
import org.maplibre.compose.mlnffi.VulkanImageTarget

/**
 * Bridges MapLibre's Vulkan rendering into Compose's Direct3D 12 context on Windows.
 *
 * Both sides must agree on the pixel format: `DXGI_FORMAT_B8G8R8A8_UNORM` and
 * `VK_FORMAT_B8G8R8A8_UNORM`.
 */
internal class VulkanDirect3D12MapHost(private val gpuHost: ComposeMapHost) : MlnFfiMapHost {
  private val rendererThread = MapRendererThread("maplibre-windows-vulkan-renderer")
  private val presenter = Direct3D12Presenter(gpuHost)
  private val frameCompletion = ComposeFrameCompletion()
  private var vulkan: WindowsVulkanContext? = null
  private var direct3DTexture = NativeHandle(0)
  private var importedTexture: WindowsVulkanImportedDirect3DTexture? = null
  private val retiredTextures = mutableMapOf<Long, Direct3DTextureTarget>()
  private var generation = 0L
  private var currentExtent = MapExtent.Empty
  private var currentDevice = NativeHandle(0)

  override val backends: RenderBackendPair =
    RenderBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.DIRECT3D12)

  override fun resize(extent: MapExtent) {
    // The device must be read on the caller's thread; reading it from the renderer thread hops to
    // the GPU thread, which is usually the thread blocked on this call.
    val device = if (extent.isEmpty) null else currentDeviceOrNull() ?: return
    resize(extent, device)
  }

  private fun resize(extent: MapExtent, device: NativeHandle?) {
    // Retired textures stay presentable until Compose has drawn a newer generation; a resize can
    // race ahead of that draw, and releasing here would make the map flash transparent.
    val result = rendererThread.run { resizeOnRendererThread(extent, device) }
    if (result.failure != null) {
      result.retired.forEach { releaseDirect3DTexture(it.texture) }
      throw result.failure
    }
    result.retired.singleOrNull()?.let { retiredTextures[it.generation] = it }
  }

  /** Reallocates the texture, detaching every target the caller must release or retain. */
  private fun resizeOnRendererThread(extent: MapExtent, device: NativeHandle?): ResizeResult {
    if (extent == currentExtent && importedTexture != null && device == currentDevice) {
      return ResizeResult()
    }
    val deviceChanged = !currentDevice.isNull && device != currentDevice
    val retired = mutableListOf<Direct3DTextureTarget>()
    retireTexture()?.let(retired::add)
    if (deviceChanged) {
      val closing = vulkan
      vulkan = null
      closing?.close()
    }
    try {
      recreateTexture(extent, device)
    } catch (error: Throwable) {
      retireTexture()?.let(retired::add)
      return ResizeResult(retired, error)
    }
    currentExtent = extent
    generation += 1
    return ResizeResult(retired)
  }

  override fun acquireFrame(
    frameId: Long,
    extent: MapExtent,
    presentationTimeNanos: Long?,
  ): MlnFfiMapFrameAcquisition {
    val context = withPreparedContext { it } ?: return MlnFfiMapFrameAcquisition.NotReady
    val device = context.device
    if (importedTexture == null || extent != currentExtent || device != currentDevice) {
      resize(extent, device)
    }
    return MlnFfiMapFrameAcquisition.Acquired(
      MlnFfiMapFrame(
        frameId = frameId,
        extent = extent,
        target = target(generation),
        presentationTimeNanos = presentationTimeNanos,
      )
    )
  }

  override fun completeProducerAccess(frame: MlnFfiMapFrame) {
    rendererThread.run { vulkan?.waitIdle() }
  }

  override fun <T> withProducerAccess(frame: MlnFfiMapFrame, action: () -> T): T =
    rendererThread.run(action)

  override fun <T> withRendererAccess(action: () -> T): T = rendererThread.run(action)

  override fun enqueueRenderer(action: () -> Unit): Boolean = rendererThread.post(action)

  override fun draw(scope: DrawScope, target: MlnFfiRenderTarget): Boolean {
    if (target !is VulkanImageTarget) return false
    val direct3DTarget =
      if (target.generation == generation) presentationTarget()
      else retiredTextures[target.generation]
    if (direct3DTarget == null) return false
    val drew =
      withPreparedContext { context ->
        presenter.draw(scope, context.skiaContext, direct3DTarget, frameCompletion)
      } ?: false
    if (drew) disposeRetiredTextures(exceptGeneration = target.generation)
    return drew
  }

  override fun close() {
    try {
      frameCompletion.abandon()
      // Released on the closing thread, never the renderer thread; see releaseDirect3DTexture.
      retireTexture()?.let { releaseDirect3DTexture(it.texture) }
      disposeRetiredTextures()
      presenter.close()
    } finally {
      val closingVulkan = vulkan
      vulkan = null
      try {
        closingVulkan?.close()
      } finally {
        rendererThread.close()
      }
    }
  }

  private fun target(generation: Long): MlnFfiRenderTarget =
    checkNotNull(importedTexture) { "Windows Vulkan texture is not initialized" }.target(generation)

  /** Allocates the texture for [extent] after the previous target has been retired. */
  private fun recreateTexture(extent: MapExtent, device: NativeHandle?) {
    if (extent.isEmpty) return

    val direct3DDevice =
      checkNotNull(device) { "resize() resolves the Direct3D device before this hop" }
    val storageExtent = extent
    direct3DTexture = WindowsDirect3DInterop.createSharedTexture(direct3DDevice, storageExtent)
    currentDevice = direct3DDevice
    var sharedHandle = NULL
    try {
      sharedHandle = WindowsDirect3DInterop.createSharedHandle(direct3DTexture)
      // The shared handle doubles as the probe for picking an importing Vulkan device, so the
      // context cannot be created before there is a texture to share.
      val context = vulkan ?: WindowsVulkanContext.create(sharedHandle).also { vulkan = it }
      importedTexture = context.importDirect3DTexture(sharedHandle, storageExtent, extent)
    } finally {
      // Vulkan duplicates the handle rather than taking ownership, so this copy is always ours.
      WindowsDirect3DInterop.closeSharedHandle(sharedHandle)
    }
  }

  /**
   * Detaches the current texture, returning the presentation target for the caller to retain or
   * release off the renderer thread.
   */
  private fun retireTexture(): Direct3DTextureTarget? {
    val retired = presentationTarget()
    importedTexture?.close()
    importedTexture = null
    direct3DTexture = NativeHandle(0)
    currentDevice = NativeHandle(0)
    return retired
  }

  private fun currentDeviceOrNull(): NativeHandle? {
    return withPreparedContext { it.device }
  }

  private fun <T> withPreparedContext(action: (Direct3D12ComposeGpuContext) -> T): T? =
    gpuHost.onGpuThread {
      val context = gpuHost.gpuContext() ?: return@onGpuThread null
      val direct3DContext =
        context as? Direct3D12ComposeGpuContext
          ?: throw MlnFfiHostException(
            "${gpuHost.description} switched from Direct3D12ComposeGpuContext to " +
              context::class.simpleName
          )
      frameCompletion.prepare(direct3DContext.skiaContext, presenter::resetContext)
      action(direct3DContext)
    }

  private fun presentationTarget(): Direct3DTextureTarget? {
    if (direct3DTexture.address == 0L) return null
    return Direct3DTextureTarget(
      texture = direct3DTexture,
      // Skia wraps the D3D12 resource, so it needs the allocated size, not the render size.
      extent = importedTexture?.storageExtent ?: currentExtent,
      generation = generation,
    )
  }

  private fun disposeRetiredTextures(exceptGeneration: Long? = null) {
    val iterator = retiredTextures.iterator()
    while (iterator.hasNext()) {
      val entry = iterator.next()
      if (entry.key != exceptGeneration) {
        releaseDirect3DTexture(entry.value.texture)
        iterator.remove()
      }
    }
  }

  /**
   * Never call this from the renderer thread: dropping the Skia wrapper waits on the GPU thread,
   * which is usually the thread blocked on a renderer hop.
   */
  private fun releaseDirect3DTexture(texture: NativeHandle) {
    if (texture.address == 0L) return
    // Skia holds a surface wrapping this texture; it must be dropped before the texture is.
    presenter.forget(texture)
    WindowsDirect3DInterop.release(texture)
  }

  private data class ResizeResult(
    val retired: List<Direct3DTextureTarget> = emptyList(),
    val failure: Throwable? = null,
  )
}

/** The Vulkan instance, device, and queue MapLibre renders with on Windows. */
private class WindowsVulkanContext private constructor(private val sharedHandle: Long) :
  AutoCloseable {
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

  fun importDirect3DTexture(
    sharedHandle: Long,
    storageExtent: MapExtent,
    renderExtent: MapExtent,
  ): WindowsVulkanImportedDirect3DTexture =
    WindowsVulkanImportedDirect3DTexture.create(this, sharedHandle, storageExtent, renderExtent)

  fun waitIdle() {
    device?.let { checkVulkan(vkDeviceWaitIdle(it), "vkDeviceWaitIdle") }
  }

  fun physicalDevice(): VkPhysicalDevice =
    checkNotNull(physicalDevice) { "Vulkan physical device is not initialized" }

  fun device(): VkDevice = checkNotNull(device) { "Vulkan device is not initialized" }

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

  /**
   * There is no device UUID to match against, so each candidate is probed for the shared handle.
   */
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
      for (index in 0..<devices.capacity()) {
        val candidate = VkPhysicalDevice(devices[index], instance())
        if (
          VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME !in stack.vulkanDeviceExtensions(candidate)
        ) {
          continue
        }
        val queueFamily = stack.findVulkanGraphicsQueueFamily(candidate)
        if (queueFamily >= 0 && canImportDirect3DHandle(candidate, queueFamily)) {
          physicalDevice = candidate
          graphicsQueueFamilyIndex = queueFamily
          return
        }
      }
      throw MlnFfiHostException(
        "No Vulkan device supports graphics, $VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME, and " +
          "importing Compose's Direct3D 12 texture"
      )
    }
  }

  /**
   * Whether [candidate] can import the shared handle. `vkGetMemoryWin32HandlePropertiesKHR` is
   * device-level, so this creates a throwaway device to ask.
   */
  private fun canImportDirect3DHandle(candidate: VkPhysicalDevice, queueFamily: Int): Boolean {
    MemoryStack.stackPush().use { stack ->
      val deviceExtensions = stack.vulkanDeviceExtensions(candidate)
      val extensions = LinkedHashSet<String>()
      extensions.add(VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME)
      if (VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME in deviceExtensions) {
        extensions.add(VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME)
      }
      val priorities = stack.floats(1.0f)
      val queueInfo =
        VkDeviceQueueCreateInfo.calloc(1, stack)
          .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
          .queueFamilyIndex(queueFamily)
          .pQueuePriorities(priorities)
      val createInfo =
        VkDeviceCreateInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
          .pQueueCreateInfos(queueInfo)
          .ppEnabledExtensionNames(stack.vulkanStringBuffer(extensions))
      val out = stack.mallocPointer(1)
      if (vkCreateDevice(candidate, createInfo, null, out) != VK_SUCCESS) {
        return false
      }
      val probeDevice = VkDevice(out[0], candidate, createInfo)
      return try {
        val handleProperties =
          VkMemoryWin32HandlePropertiesKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_MEMORY_WIN32_HANDLE_PROPERTIES_KHR)
        vkGetMemoryWin32HandlePropertiesKHR(
          probeDevice,
          VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_RESOURCE_BIT,
          sharedHandle,
          handleProperties,
        ) == VK_SUCCESS && handleProperties.memoryTypeBits() != 0
      } finally {
        vkDestroyDevice(probeDevice, null)
      }
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
    fun create(sharedHandle: Long): WindowsVulkanContext {
      val context = WindowsVulkanContext(sharedHandle)
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

/** MapLibre's view of Compose's D3D12 texture, imported into Vulkan as a `VkImage`. */
private class WindowsVulkanImportedDirect3DTexture
private constructor(
  private val context: WindowsVulkanContext,
  private val sharedHandle: Long,
  /** The size the D3D12 resource was allocated at, which is what the `VkImage` must match. */
  val storageExtent: MapExtent,
  private val renderExtent: MapExtent,
) : AutoCloseable {
  private var image = NULL
  private var memory = NULL
  private var view = NULL

  fun target(generation: Long): VulkanImageTarget =
    VulkanImageTarget(
      context = context.handles,
      image = NativeHandle(image),
      imageView = NativeHandle(view),
      format = VK_FORMAT_B8G8R8A8_UNORM,
      initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
      finalLayout = VK_IMAGE_LAYOUT_GENERAL,
      queueFamilyIndex = context.handles.graphicsQueueFamilyIndex,
      extent = renderExtent,
      generation = generation,
    )

  private fun create() {
    MemoryStack.stackPush().use { stack ->
      val externalImageInfo =
        VkExternalMemoryImageCreateInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO)
          .handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_RESOURCE_BIT)
      val imageInfo =
        VkImageCreateInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
          .pNext(externalImageInfo.address())
          .imageType(VK_IMAGE_TYPE_2D)
          .format(VK_FORMAT_B8G8R8A8_UNORM)
          .extent(
            VkExtent3D.calloc(stack)
              .width(storageExtent.physicalWidth)
              .height(storageExtent.physicalHeight)
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
          VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_RESOURCE_BIT,
          sharedHandle,
          handleProperties,
        ),
        "vkGetMemoryWin32HandlePropertiesKHR",
      )
      val importInfo =
        VkImportMemoryWin32HandleInfoKHR.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_IMPORT_MEMORY_WIN32_HANDLE_INFO_KHR)
          .handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_RESOURCE_BIT)
          .handle(sharedHandle)
      // A D3D12 resource handle names a whole resource rather than a suballocatable heap, so the
      // import must be a dedicated allocation bound to exactly this image.
      val dedicated =
        VkMemoryDedicatedAllocateInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO)
          .image(image)
      importInfo.pNext(dedicated.address())
      val allocateInfo =
        VkMemoryAllocateInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
          .pNext(importInfo.address())
          .allocationSize(requirements.size())
          .memoryTypeIndex(
            findVulkanDeviceLocalMemoryType(
              context.physicalDevice(),
              requirements.memoryTypeBits() and handleProperties.memoryTypeBits(),
              "No compatible Vulkan memory type found for imported D3D12 resource",
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
    if (memory != NULL) {
      vkFreeMemory(context.device(), memory, null)
      memory = NULL
    }
  }

  companion object {
    fun create(
      context: WindowsVulkanContext,
      sharedHandle: Long,
      storageExtent: MapExtent,
      renderExtent: MapExtent,
    ): WindowsVulkanImportedDirect3DTexture {
      val texture =
        WindowsVulkanImportedDirect3DTexture(context, sharedHandle, storageExtent, renderExtent)
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
