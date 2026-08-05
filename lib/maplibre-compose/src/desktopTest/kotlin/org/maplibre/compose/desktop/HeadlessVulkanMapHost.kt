package org.maplibre.compose.desktop

import androidx.compose.ui.graphics.drawscope.DrawScope
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR
import org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME
import org.lwjgl.vulkan.KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME
import org.lwjgl.vulkan.VK10.VK_API_VERSION_1_0
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
import org.lwjgl.vulkan.VkApplicationInfo
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkDeviceCreateInfo
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo
import org.lwjgl.vulkan.VkExtent3D
import org.lwjgl.vulkan.VkImageCreateInfo
import org.lwjgl.vulkan.VkImageSubresourceRange
import org.lwjgl.vulkan.VkImageViewCreateInfo
import org.lwjgl.vulkan.VkInstance
import org.lwjgl.vulkan.VkInstanceCreateInfo
import org.lwjgl.vulkan.VkMemoryAllocateInfo
import org.lwjgl.vulkan.VkMemoryRequirements
import org.lwjgl.vulkan.VkPhysicalDevice
import org.lwjgl.vulkan.VkQueue
import org.maplibre.compose.desktop.skiko.HostRendererThread
import org.maplibre.compose.desktop.skiko.checkVulkan
import org.maplibre.compose.desktop.skiko.ensureVulkanFunctionProvider
import org.maplibre.compose.desktop.skiko.findVulkanDeviceLocalMemoryType
import org.maplibre.compose.desktop.skiko.findVulkanGraphicsQueueFamily
import org.maplibre.compose.desktop.skiko.vulkanDeviceExtensions
import org.maplibre.compose.desktop.skiko.vulkanFunctionAddress
import org.maplibre.compose.desktop.skiko.vulkanInstanceExtensions
import org.maplibre.compose.desktop.skiko.vulkanStringBuffer

/**
 * A [DesktopMapHost] that renders on a real GPU with no window.
 *
 * [FakeDesktopMapHost] stops at the graphics boundary: it hands out invented handles, so MapLibre
 * never attaches a render session and nothing below `render()` is exercised. This host closes that
 * gap. It creates a genuine Vulkan device and a genuine `VkImage`, so MapLibre attaches, parses the
 * style, loads tiles, rasterizes, and answers rendered-feature queries exactly as it does under a
 * window — the only thing missing is the compositing step, which is why [draw] does nothing.
 *
 * That makes it the vehicle for testing the whole desktop stack: a bug in style JSON, layer
 * ordering, expression compilation, or query conversion surfaces here, in a test, instead of only
 * as a dialog in the demo app.
 *
 * Unlike the shipped Linux host this asks for no external-memory extensions, because nothing
 * imports the image. It therefore runs on any Vulkan implementation, including a software one such
 * as lavapipe, which is what makes it viable in CI.
 */
internal class HeadlessVulkanMapHost private constructor() : DesktopMapHost {

  private val rendererThread = HostRendererThread("maplibre-headless-vulkan")
  private var context: HeadlessVulkanContext? = null
  private var texture: HeadlessVulkanTexture? = null
  private var generation = 0L

  /** The extent the current texture was allocated at; the map's viewport, in logical pixels. */
  var currentExtent: DesktopMapExtent = DesktopMapExtent.Empty
    private set

  /**
   * Frames this host handed out.
   *
   * Asserted on by tests: a composition that never acquires a frame never reaches MapLibre at all,
   * and would otherwise pass by doing nothing.
   */
  var acquiredFrames: Int = 0
    private set

  /**
   * Frames MapLibre actually rendered into, as opposed to acquired and skipped.
   *
   * This is the signal for "did the map redraw": a state change that should be visible must produce
   * one, and a change that produces none is invisible until something else wakes the loop.
   */
  var renderedFrames: Int = 0
    private set

  override val backends: DesktopBackendPair =
    DesktopBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL)

  override fun resize(extent: DesktopMapExtent) {
    // Allocation happens in acquireFrame, matching the shipped hosts.
  }

  override fun acquireFrame(
    frameId: Long,
    extent: DesktopMapExtent,
    presentationTimeNanos: Long?,
  ): DesktopMapFrame = rendererThread.run {
    if (texture == null || extent != currentExtent) recreateTexture(extent)
    acquiredFrames++
    HostFrame(
      frameId = frameId,
      extent = extent,
      target = requireNotNull(texture) { "Vulkan texture is not initialized" }.target(generation),
      presentationTimeNanos = presentationTimeNanos,
    )
  }

  override fun completeProducerAccess(frame: DesktopMapFrame) {
    renderedFrames++
    rendererThread.run { context?.waitIdle() }
  }

  override fun releaseFrame(frame: DesktopMapFrame) {
    // The single texture is reused across frames, so there is nothing per-frame to release.
  }

  override fun <T> withProducerAccess(frame: DesktopMapFrame, action: () -> T): T =
    rendererThread.run(action)

  override fun <T> withRendererAccess(action: () -> T): T = rendererThread.run(action)

  /** Nothing composites the result, so a headless frame is never drawn. */
  override fun draw(scope: DrawScope, target: DesktopRenderTarget): Boolean = false

  override fun close() {
    rendererThread.run {
      texture?.close()
      texture = null
      context?.close()
      context = null
    }
    rendererThread.close()
  }

  private fun recreateTexture(extent: DesktopMapExtent) {
    texture?.close()
    texture = null
    if (extent.isEmpty) {
      currentExtent = DesktopMapExtent.Empty
      generation += 1
      return
    }
    val vulkan = context ?: HeadlessVulkanContext.create().also { context = it }
    texture = HeadlessVulkanTexture.create(vulkan, extent)
    currentExtent = extent
    generation += 1
  }

  private class HostFrame(
    override val frameId: Long,
    override val extent: DesktopMapExtent,
    override val target: DesktopRenderTarget,
    override val presentationTimeNanos: Long?,
  ) : DesktopMapFrame

  companion object {
    /**
     * Creates a host, or fails.
     *
     * A working Vulkan implementation is a requirement of this suite, not a nice-to-have, and
     * saying so with an exception is the whole point. The alternative — returning null so callers
     * can bail out early — reads as a skip but is not one: a test that returns before asserting is
     * recorded by JUnit as **passed**. A machine without a Vulkan loader would then run every
     * GPU-backed test green while executing none of them, which is worse than a red suite because
     * nothing distinguishes it from real coverage.
     *
     * On macOS that means the Homebrew `vulkan-loader` and `molten-vk` that `mise run bootstrap`
     * installs; Linux and Windows have a system loader.
     */
    fun create(): HeadlessVulkanMapHost {
      val host = HeadlessVulkanMapHost()
      return try {
        host.rendererThread.run { host.recreateTexture(PROBE_EXTENT) }
        host
      } catch (error: Throwable) {
        runCatching { host.close() }
        throw IllegalStateException(
          "No usable Vulkan implementation, so the desktop GPU tests cannot run. On macOS, " +
            "`mise run bootstrap` installs vulkan-loader and molten-vk; elsewhere install the " +
            "system Vulkan loader. Probe failed with: ${error.message}",
          error,
        )
      }
    }

    /** Small enough to allocate anywhere, large enough to prove the device really works. */
    private val PROBE_EXTENT =
      DesktopMapExtent.fromLogical(width = 16, height = 16, scaleFactor = 1.0)
  }
}

/** A minimal Vulkan instance, device, and graphics queue, with no surface or extension demands. */
internal class HeadlessVulkanContext private constructor() : AutoCloseable {
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
      // MoltenVK is a portability driver and refuses to enumerate its devices without this.
      val portability = VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME in available
      if (portability) extensions.add(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME)

      val appInfo =
        VkApplicationInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
          .pApplicationName(stack.UTF8("maplibre-compose headless test"))
          .apiVersion(VK_API_VERSION_1_0)
      val createInfo =
        VkInstanceCreateInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
          .pApplicationInfo(appInfo)
          .flags(if (portability) VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR else 0)
      if (extensions.isNotEmpty()) {
        createInfo.ppEnabledExtensionNames(stack.vulkanStringBuffer(extensions))
      }
      val out = stack.mallocPointer(1)
      checkVulkan(vkCreateInstance(createInfo, null, out), "vkCreateInstance")
      instance = VkInstance(out[0], createInfo)
    }
  }

  private fun pickPhysicalDeviceAndQueue() {
    MemoryStack.stackPush().use { stack ->
      val count = stack.mallocInt(1)
      checkVulkan(vkEnumeratePhysicalDevices(instance(), count, null), "vkEnumeratePhysicalDevices")
      check(count[0] > 0) { "No Vulkan physical devices are available" }
      val devices = stack.mallocPointer(count[0])
      checkVulkan(
        vkEnumeratePhysicalDevices(instance(), count, devices),
        "vkEnumeratePhysicalDevices",
      )
      for (index in 0..<devices.capacity()) {
        val candidate = VkPhysicalDevice(devices[index], instance())
        val family = stack.findVulkanGraphicsQueueFamily(candidate)
        if (family < 0) continue
        physicalDevice = candidate
        graphicsQueueFamilyIndex = family
        return
      }
      error("No Vulkan device offers a graphics queue")
    }
  }

  private fun createDevice() {
    MemoryStack.stackPush().use { stack ->
      val available = stack.vulkanDeviceExtensions(physicalDevice())
      val extensions = LinkedHashSet<String>()
      if (VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME in available) {
        extensions.add(VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME)
      }
      val queueInfo =
        VkDeviceQueueCreateInfo.calloc(1, stack)
          .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
          .queueFamilyIndex(graphicsQueueFamilyIndex)
          .pQueuePriorities(stack.floats(1.0f))
      val createInfo =
        VkDeviceCreateInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
          .pQueueCreateInfos(queueInfo)
      if (extensions.isNotEmpty()) {
        createInfo.ppEnabledExtensionNames(stack.vulkanStringBuffer(extensions))
      }
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
    fun create(): HeadlessVulkanContext {
      val context = HeadlessVulkanContext()
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

/** A device-local `VkImage` for MapLibre to render into. */
internal class HeadlessVulkanTexture
private constructor(
  private val context: HeadlessVulkanContext,
  private val extent: DesktopMapExtent,
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
      val imageInfo =
        VkImageCreateInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
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
      val allocateInfo =
        VkMemoryAllocateInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
          .allocationSize(requirements.size())
          .memoryTypeIndex(
            findVulkanDeviceLocalMemoryType(
              context.physicalDevice(),
              requirements.memoryTypeBits(),
              "No compatible Vulkan memory type found",
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
    val device = context.device()
    if (view != NULL) {
      vkDestroyImageView(device, view, null)
      view = NULL
    }
    if (image != NULL) {
      vkDestroyImage(device, image, null)
      image = NULL
    }
    if (memory != NULL) {
      vkFreeMemory(device, memory, null)
      memory = NULL
    }
  }

  companion object {
    fun create(context: HeadlessVulkanContext, extent: DesktopMapExtent): HeadlessVulkanTexture {
      val texture = HeadlessVulkanTexture(context, extent)
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
