package org.maplibre.compose.desktop.bridge

import androidx.compose.ui.graphics.drawscope.DrawScope
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
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
import org.maplibre.compose.desktop.ComposeGpuHost
import org.maplibre.compose.desktop.Direct3D12ComposeGpuContext
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiHostException
import org.maplibre.compose.mlnffi.MlnFfiMapExtent
import org.maplibre.compose.mlnffi.MlnFfiMapFrame
import org.maplibre.compose.mlnffi.MlnFfiMapHost
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.compose.mlnffi.NativeHandle
import org.maplibre.compose.mlnffi.RenderBackendPair
import org.maplibre.compose.mlnffi.VulkanContextHandles
import org.maplibre.compose.mlnffi.VulkanImageTarget

/**
 * Bridges MapLibre's Vulkan rendering into Compose's Direct3D 12 context on Windows.
 *
 * Unlike Linux, Compose's `ID3D12Device` allocates the shared texture and Vulkan imports the
 * resulting NT handle. Because that handle names a D3D12 resource rather than opaque memory, both
 * sides must agree on the pixel format: `DXGI_FORMAT_B8G8R8A8_UNORM` and
 * `VK_FORMAT_B8G8R8A8_UNORM`.
 *
 * Ported from the `maplibre-native-ffi` Compose example.
 */
internal class VulkanDirect3D12MapHost(private val gpuHost: ComposeGpuHost) : MlnFfiMapHost {
  private val rendererThread = MapRendererThread("maplibre-windows-vulkan-renderer")
  private val presenter = Direct3D12Presenter(gpuHost)
  private var vulkan: WindowsVulkanContext? = null
  private var direct3DTexture = NativeHandle(0)
  private var importedTexture: WindowsVulkanImportedDirect3DTexture? = null
  private val retiredTextures = mutableMapOf<Long, Direct3DTextureTarget>()
  private var generation = 0L
  private var currentExtent = MlnFfiMapExtent.Empty

  override val backends: RenderBackendPair =
    RenderBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.DIRECT3D12)

  override fun resize(extent: MlnFfiMapExtent) {
    // The device must be read on the caller's thread; reading it from the renderer thread hops to
    // the GPU thread, which is usually the thread blocked on this call.
    val device =
      if (extent.isEmpty) null
      else {
        val context = gpuHost.currentContext() ?: return
        (context as? Direct3D12ComposeGpuContext)?.device
          ?: throw MlnFfiHostException(
            "${gpuHost.description} switched from Direct3D12ComposeGpuContext to " +
              context::class.simpleName
          )
      }
    // Retired textures come back to the GPU thread, where they remain presentable until Compose
    // has drawn a newer generation. A resize can race ahead of the draw that presents the last
    // completed frame, and releasing that frame here would make the map flash transparent.
    val retired = rendererThread.run { resizeOnRendererThread(extent, device) }
    retired?.let { retiredTextures[it.generation] = it }
  }

  /** Reallocates the texture, returning the presentation target it replaced. */
  private fun resizeOnRendererThread(
    extent: MlnFfiMapExtent,
    device: NativeHandle?,
  ): Direct3DTextureTarget? {
    if (extent == currentExtent && importedTexture != null) {
      return null
    }
    val retired = recreateTexture(extent, device)
    currentExtent = extent
    generation += 1
    return retired
  }

  override fun acquireFrame(
    frameId: Long,
    extent: MlnFfiMapExtent,
    presentationTimeNanos: Long?,
  ): MlnFfiMapFrame {
    if (importedTexture == null || extent != currentExtent) {
      resize(extent)
    }
    return MlnFfiMapFrame(
      frameId = frameId,
      extent = extent,
      target = target(generation),
      presentationTimeNanos = presentationTimeNanos,
    )
  }

  override fun completeProducerAccess(frame: MlnFfiMapFrame) {
    rendererThread.run { vulkan?.waitIdle() }
  }

  override fun <T> withProducerAccess(frame: MlnFfiMapFrame, action: () -> T): T =
    rendererThread.run(action)

  override fun <T> withRendererAccess(action: () -> T): T = rendererThread.run(action)

  override fun draw(scope: DrawScope, target: MlnFfiRenderTarget): Boolean {
    if (target !is VulkanImageTarget) return false
    val direct3DTarget =
      if (target.generation == generation) presentationTarget()
      else retiredTextures[target.generation]
    if (direct3DTarget == null) return false
    val context = gpuHost.currentContext() as? Direct3D12ComposeGpuContext ?: return false
    val drew = presenter.draw(scope, context.skiaContext, direct3DTarget)
    if (drew) disposeRetiredTextures(exceptGeneration = target.generation)
    return drew
  }

  override fun close() {
    try {
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

  /** Allocates the texture for [extent], returning the presentation target it replaced, if any. */
  private fun recreateTexture(
    extent: MlnFfiMapExtent,
    device: NativeHandle?,
  ): Direct3DTextureTarget? {
    if (extent.isEmpty) return retireTexture()

    // An assertion, not a fallback: asking the host for a device here would hop to the GPU thread,
    // which is the thread waiting on this hop.
    val direct3DDevice =
      checkNotNull(device) { "resize() resolves the Direct3D device before this hop" }
    val storageExtent = extent
    val retired = retireTexture()
    direct3DTexture = WindowsDirect3DInterop.createSharedTexture(direct3DDevice, storageExtent)
    var sharedHandle = NULL
    try {
      sharedHandle = WindowsDirect3DInterop.createSharedHandle(direct3DTexture)
      // The first shared handle doubles as the probe for picking an importing Vulkan device, so the
      // context cannot be created before there is a texture to share.
      val context = vulkan ?: WindowsVulkanContext.create(sharedHandle).also { vulkan = it }
      importedTexture = context.importDirect3DTexture(sharedHandle, storageExtent, extent)
    } catch (error: RuntimeException) {
      // The half-built texture goes now; the one it replaced still goes back to the caller.
      retireTexture()?.let { releaseDirect3DTexture(it.texture) }
      throw error
    } finally {
      // Vulkan duplicates the handle rather than taking ownership, so this copy is always ours.
      WindowsDirect3DInterop.closeSharedHandle(sharedHandle)
    }
    return retired
  }

  /**
   * Releases the Vulkan import on the renderer thread and detaches the D3D texture, returning the
   * presentation target for the caller to retain or release off the renderer thread.
   */
  private fun retireTexture(): Direct3DTextureTarget? {
    val retired = presentationTarget()
    importedTexture?.close()
    importedTexture = null
    direct3DTexture = NativeHandle(0)
    return retired
  }

  /** The current D3D resource in the shape Skia needs to present it. */
  private fun presentationTarget(): Direct3DTextureTarget? {
    if (direct3DTexture.address == 0L) return null
    return Direct3DTextureTarget(
      texture = direct3DTexture,
      // Skia wraps the D3D12 resource, so it needs the allocated size, not the render size.
      extent = importedTexture?.storageExtent ?: currentExtent,
      generation = generation,
    )
  }

  /** Releases retired generations other than the one Compose just presented. */
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
   * Releases a retired D3D texture, and the Skia surface wrapping it, on the calling thread.
   *
   * Never call this from the renderer thread: dropping the Skia wrapper waits on the GPU thread,
   * which is usually the thread blocked on a renderer hop.
   */
  private fun releaseDirect3DTexture(texture: NativeHandle) {
    if (texture.address == 0L) return
    // Skia holds a surface wrapping this texture; it must be dropped before the texture is.
    presenter.forget(texture)
    WindowsDirect3DInterop.release(texture)
  }
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
    storageExtent: MlnFfiMapExtent,
    renderExtent: MlnFfiMapExtent,
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
   * Picks the physical device MapLibre renders on by asking each candidate whether it can import
   * Compose's shared handle — there is no device UUID to match against, unlike Linux.
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
   * Whether [candidate] can import the shared handle, tested on a throwaway device.
   *
   * `vkGetMemoryWin32HandlePropertiesKHR` is a device-level call, so answering at all requires
   * creating a device first; [createDevice] later creates the real one.
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
  val storageExtent: MlnFfiMapExtent,
  private val renderExtent: MlnFfiMapExtent,
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
      storageExtent: MlnFfiMapExtent,
      renderExtent: MlnFfiMapExtent,
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

/**
 * The slice of Direct3D 12 this host needs, called through the JDK's foreign function API.
 *
 * There is no D3D12 Java binding on the classpath, so COM interfaces are invoked by hand through
 * their vtables. The vtable indices below come from declaration order in `d3d12.h`; they are
 * ABI-stable but unchecked, and a wrong index calls the wrong method rather than failing.
 */
private object WindowsDirect3DInterop {
  private const val IID_ID3D12_DEVICE_DATA1 = 0x189819F1
  private const val IID_ID3D12_DEVICE_DATA2 = 0x1DB6
  private const val IID_ID3D12_DEVICE_DATA3 = 0x4B57
  private const val IID_ID3D12_RESOURCE_DATA1 = 0x696442BE
  private const val IID_ID3D12_RESOURCE_DATA2 = 0xA72E
  private const val IID_ID3D12_RESOURCE_DATA3 = 0x4059
  private const val GENERIC_ALL = 0x10000000

  private const val D3D12_HEAP_TYPE_DEFAULT = 1
  private const val D3D12_HEAP_FLAG_SHARED = 0x1
  private const val D3D12_RESOURCE_DIMENSION_TEXTURE2D = 3
  private const val D3D12_RESOURCE_STATE_COMMON = 0
  private const val D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET = 0x1
  private const val D3D12_TEXTURE_LAYOUT_UNKNOWN = 0
  private const val ID3D12_DEVICE_CHILD_GET_DEVICE_INDEX = 7
  private const val ID3D12_DEVICE_CREATE_COMMITTED_RESOURCE_INDEX = 27
  private const val ID3D12_DEVICE_CREATE_SHARED_HANDLE_INDEX = 31
  private const val IUNKNOWN_RELEASE_INDEX = 2

  private val linker = Linker.nativeLinker()
  private val kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global())
  private val closeHandle =
    linker.downcallHandle(
      kernel32.findOrThrow("CloseHandle"),
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
    )

  /**
   * Allocates a shareable `ID3D12Resource` texture on Compose's device. `D3D12_HEAP_FLAG_SHARED`
   * makes it eligible for [createSharedHandle]; `ALLOW_RENDER_TARGET` lets Skia treat it as a
   * render target once wrapped.
   */
  fun createSharedTexture(
    device: NativeHandle,
    extent: MlnFfiMapExtent,
    dxgiFormat: Int = DXGI_FORMAT_B8G8R8A8_UNORM,
  ): NativeHandle {
    check(!extent.isEmpty) { "Cannot create a D3D12 texture for an empty extent" }
    Arena.ofConfined().use { arena ->
      val rawDevice = device.address
      val resourceOut = arena.allocate(ValueLayout.ADDRESS)
      checkHResult(
        invokeHResult(
          comMethod(rawDevice, ID3D12_DEVICE_CREATE_COMMITTED_RESOURCE_INDEX),
          address(rawDevice),
          heapProperties(arena),
          D3D12_HEAP_FLAG_SHARED,
          textureDesc(arena, extent, dxgiFormat),
          D3D12_RESOURCE_STATE_COMMON,
          MemorySegment.NULL,
          iidId3D12Resource(arena),
          resourceOut,
        ),
        "ID3D12Device::CreateCommittedResource",
      )
      val resource = resourceOut.get(ValueLayout.ADDRESS, 0).address()
      check(resource != NULL) { "ID3D12Device::CreateCommittedResource returned null" }
      return NativeHandle(resource)
    }
  }

  /**
   * Opens an NT handle naming [resource], which Vulkan can import. The handle belongs to the
   * caller, who must close it once the import has duplicated it.
   */
  fun createSharedHandle(resource: NativeHandle): Long {
    check(resource.address != 0L) { "Cannot share a null D3D12 resource" }
    Arena.ofConfined().use { arena ->
      val deviceOut = arena.allocate(ValueLayout.ADDRESS)
      checkHResult(
        invokeHResult(
          comMethod(resource.address, ID3D12_DEVICE_CHILD_GET_DEVICE_INDEX),
          address(resource.address),
          iidId3D12Device(arena),
          deviceOut,
        ),
        "ID3D12Resource::GetDevice",
      )
      val device = deviceOut.get(ValueLayout.ADDRESS, 0).address()
      try {
        val handleOut = arena.allocate(ValueLayout.ADDRESS)
        checkHResult(
          invokeHResult(
            comMethod(device, ID3D12_DEVICE_CREATE_SHARED_HANDLE_INDEX),
            address(device),
            address(resource.address),
            MemorySegment.NULL,
            GENERIC_ALL,
            MemorySegment.NULL,
            handleOut,
          ),
          "ID3D12Device::CreateSharedHandle",
        )
        val handle = handleOut.get(ValueLayout.ADDRESS, 0).address()
        check(handle != NULL) { "ID3D12Device::CreateSharedHandle returned a null handle" }
        return handle
      } finally {
        // GetDevice addrefs its result, so this releases our reference, not Compose's device.
        release(device)
      }
    }
  }

  fun release(resource: NativeHandle) {
    release(resource.address)
  }

  fun closeSharedHandle(handle: Long) {
    if (handle != NULL) {
      closeHandle.invokeWithArguments(address(handle))
    }
  }

  /** `D3D12_HEAP_PROPERTIES` for a default (device-local) heap on the single-adapter node. */
  private fun heapProperties(arena: Arena): MemorySegment {
    val props = arena.allocate(20)
    props.set(ValueLayout.JAVA_INT, 0, D3D12_HEAP_TYPE_DEFAULT)
    props.set(ValueLayout.JAVA_INT, 4, 0)
    props.set(ValueLayout.JAVA_INT, 8, 0)
    props.set(ValueLayout.JAVA_INT, 12, 1)
    props.set(ValueLayout.JAVA_INT, 16, 1)
    return props
  }

  /** `D3D12_RESOURCE_DESC` for a single-sampled, single-mip 2D texture. */
  private fun textureDesc(arena: Arena, extent: MlnFfiMapExtent, dxgiFormat: Int): MemorySegment {
    val desc = arena.allocate(56)
    desc.set(ValueLayout.JAVA_INT, 0, D3D12_RESOURCE_DIMENSION_TEXTURE2D)
    desc.set(ValueLayout.JAVA_LONG, 8, 0)
    desc.set(ValueLayout.JAVA_LONG, 16, extent.physicalWidth.toLong())
    desc.set(ValueLayout.JAVA_INT, 24, extent.physicalHeight)
    desc.set(ValueLayout.JAVA_SHORT, 28, 1.toShort())
    desc.set(ValueLayout.JAVA_SHORT, 30, 1.toShort())
    desc.set(ValueLayout.JAVA_INT, 32, dxgiFormat)
    desc.set(ValueLayout.JAVA_INT, 36, 1)
    desc.set(ValueLayout.JAVA_INT, 40, 0)
    desc.set(ValueLayout.JAVA_INT, 44, D3D12_TEXTURE_LAYOUT_UNKNOWN)
    desc.set(ValueLayout.JAVA_INT, 48, D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET)
    return desc
  }

  /** `IID_ID3D12Device`, `{189819F1-1DB6-4B57-BE54-1821339B85F7}`. */
  private fun iidId3D12Device(arena: Arena): MemorySegment =
    guid(
      arena,
      IID_ID3D12_DEVICE_DATA1,
      IID_ID3D12_DEVICE_DATA2,
      IID_ID3D12_DEVICE_DATA3,
      0xBE,
      0x54,
      0x18,
      0x21,
      0x33,
      0x9B,
      0x85,
      0xF7,
    )

  /** `IID_ID3D12Resource`, `{696442BE-A72E-4059-BC79-5B5C98040FAD}`. */
  private fun iidId3D12Resource(arena: Arena): MemorySegment =
    guid(
      arena,
      IID_ID3D12_RESOURCE_DATA1,
      IID_ID3D12_RESOURCE_DATA2,
      IID_ID3D12_RESOURCE_DATA3,
      0xBC,
      0x79,
      0x5B,
      0x5C,
      0x98,
      0x04,
      0x0F,
      0xAD,
    )

  private fun guid(
    arena: Arena,
    data1: Int,
    data2: Int,
    data3: Int,
    vararg data4: Int,
  ): MemorySegment {
    val iid = arena.allocate(16)
    iid.set(ValueLayout.JAVA_INT, 0, data1)
    iid.set(ValueLayout.JAVA_SHORT, 4, data2.toShort())
    iid.set(ValueLayout.JAVA_SHORT, 6, data3.toShort())
    data4.forEachIndexed { index, value ->
      iid.set(ValueLayout.JAVA_BYTE, 8L + index, value.toByte())
    }
    return iid
  }

  /** The [index]th entry of [instance]'s COM vtable. */
  private fun comMethod(instance: Long, index: Int): MemorySegment {
    val vtable = address(instance).reinterpret(Long.SIZE_BYTES.toLong()).get(ValueLayout.ADDRESS, 0)
    return vtable
      .reinterpret((index + 1L) * Long.SIZE_BYTES)
      .get(ValueLayout.ADDRESS, index * Long.SIZE_BYTES.toLong())
  }

  private fun release(instance: Long) {
    if (instance != NULL) {
      invokeInt(
        comMethod(instance, IUNKNOWN_RELEASE_INDEX),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
        address(instance),
      )
    }
  }

  private fun invokeHResult(function: MemorySegment, vararg args: Any): Int =
    invokeInt(function, hresultDescriptor(args.size), *args)

  private fun invokeInt(
    function: MemorySegment,
    descriptor: FunctionDescriptor,
    vararg args: Any,
  ): Int = linker.downcallHandle(function, descriptor).invokeWithArguments(*args) as Int

  /**
   * The descriptor for an `HRESULT`-returning COM method of [argumentCount] arguments. Only the
   * three shapes this host calls are described; a wrong branch is a stack mismatch, not an
   * exception.
   */
  private fun hresultDescriptor(argumentCount: Int): FunctionDescriptor =
    when (argumentCount) {
      // ID3D12DeviceChild::GetDevice(this, riid, ppvDevice)
      3 ->
        FunctionDescriptor.of(
          ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS,
          ValueLayout.ADDRESS,
          ValueLayout.ADDRESS,
        )
      // ID3D12Device::CreateSharedHandle(this, pObject, pAttributes, Access, Name, pHandle)
      6 ->
        FunctionDescriptor.of(
          ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS,
          ValueLayout.ADDRESS,
          ValueLayout.ADDRESS,
          ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS,
          ValueLayout.ADDRESS,
        )
      // ID3D12Device::CreateCommittedResource(this, pHeapProperties, HeapFlags, pDesc,
      // InitialResourceState, pOptimizedClearValue, riidResource, ppvResource)
      8 ->
        FunctionDescriptor.of(
          ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS,
          ValueLayout.ADDRESS,
          ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS,
          ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS,
          ValueLayout.ADDRESS,
          ValueLayout.ADDRESS,
        )
      else -> error("Unsupported HRESULT function arity: $argumentCount")
    }

  private fun address(value: Long): MemorySegment = MemorySegment.ofAddress(value)

  private fun checkHResult(hr: Int, operation: String) {
    check(hr >= 0) { "$operation failed with HRESULT 0x${hr.toUInt().toString(16)}" }
  }
}
