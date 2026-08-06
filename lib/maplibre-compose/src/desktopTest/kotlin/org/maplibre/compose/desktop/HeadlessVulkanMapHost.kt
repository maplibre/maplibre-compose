package org.maplibre.compose.desktop

import androidx.compose.ui.graphics.drawscope.DrawScope
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR
import org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME
import org.lwjgl.vulkan.KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME
import org.lwjgl.vulkan.VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
import org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_READ_BIT
import org.lwjgl.vulkan.VK10.VK_API_VERSION_1_0
import org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
import org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY
import org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM
import org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED
import org.lwjgl.vulkan.VK10.VK_IMAGE_TILING_OPTIMAL
import org.lwjgl.vulkan.VK10.VK_IMAGE_TYPE_2D
import org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
import org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT
import org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT
import org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D
import org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
import org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
import org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
import org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
import org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED
import org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT
import org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO
import org.lwjgl.vulkan.VK10.vkAllocateCommandBuffers
import org.lwjgl.vulkan.VK10.vkAllocateMemory
import org.lwjgl.vulkan.VK10.vkBeginCommandBuffer
import org.lwjgl.vulkan.VK10.vkBindBufferMemory
import org.lwjgl.vulkan.VK10.vkBindImageMemory
import org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer
import org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier
import org.lwjgl.vulkan.VK10.vkCreateBuffer
import org.lwjgl.vulkan.VK10.vkCreateCommandPool
import org.lwjgl.vulkan.VK10.vkCreateDevice
import org.lwjgl.vulkan.VK10.vkCreateImage
import org.lwjgl.vulkan.VK10.vkCreateImageView
import org.lwjgl.vulkan.VK10.vkCreateInstance
import org.lwjgl.vulkan.VK10.vkDestroyBuffer
import org.lwjgl.vulkan.VK10.vkDestroyCommandPool
import org.lwjgl.vulkan.VK10.vkDestroyDevice
import org.lwjgl.vulkan.VK10.vkDestroyImage
import org.lwjgl.vulkan.VK10.vkDestroyImageView
import org.lwjgl.vulkan.VK10.vkDestroyInstance
import org.lwjgl.vulkan.VK10.vkDeviceWaitIdle
import org.lwjgl.vulkan.VK10.vkEndCommandBuffer
import org.lwjgl.vulkan.VK10.vkEnumeratePhysicalDevices
import org.lwjgl.vulkan.VK10.vkFreeMemory
import org.lwjgl.vulkan.VK10.vkGetBufferMemoryRequirements
import org.lwjgl.vulkan.VK10.vkGetDeviceQueue
import org.lwjgl.vulkan.VK10.vkGetImageMemoryRequirements
import org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceMemoryProperties
import org.lwjgl.vulkan.VK10.vkMapMemory
import org.lwjgl.vulkan.VK10.vkQueueSubmit
import org.lwjgl.vulkan.VK10.vkQueueWaitIdle
import org.lwjgl.vulkan.VK10.vkUnmapMemory
import org.lwjgl.vulkan.VkApplicationInfo
import org.lwjgl.vulkan.VkBufferCreateInfo
import org.lwjgl.vulkan.VkBufferImageCopy
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo
import org.lwjgl.vulkan.VkCommandBufferBeginInfo
import org.lwjgl.vulkan.VkCommandPoolCreateInfo
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkDeviceCreateInfo
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo
import org.lwjgl.vulkan.VkExtent3D
import org.lwjgl.vulkan.VkImageCreateInfo
import org.lwjgl.vulkan.VkImageMemoryBarrier
import org.lwjgl.vulkan.VkImageSubresourceRange
import org.lwjgl.vulkan.VkImageViewCreateInfo
import org.lwjgl.vulkan.VkInstance
import org.lwjgl.vulkan.VkInstanceCreateInfo
import org.lwjgl.vulkan.VkMemoryAllocateInfo
import org.lwjgl.vulkan.VkMemoryRequirements
import org.lwjgl.vulkan.VkPhysicalDevice
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties
import org.lwjgl.vulkan.VkQueue
import org.lwjgl.vulkan.VkSubmitInfo
import org.maplibre.compose.desktop.bridge.MapRendererThread
import org.maplibre.compose.desktop.bridge.checkVulkan
import org.maplibre.compose.desktop.bridge.ensureVulkanFunctionProvider
import org.maplibre.compose.desktop.bridge.findVulkanDeviceLocalMemoryType
import org.maplibre.compose.desktop.bridge.findVulkanGraphicsQueueFamily
import org.maplibre.compose.desktop.bridge.vulkanDeviceExtensions
import org.maplibre.compose.desktop.bridge.vulkanFunctionAddress
import org.maplibre.compose.desktop.bridge.vulkanInstanceExtensions
import org.maplibre.compose.desktop.bridge.vulkanStringBuffer

/**
 * A [DesktopMapHost] that renders on a real GPU with no window: a genuine Vulkan device and
 * `VkImage`, so MapLibre attaches and rasterizes as it does under a window. Nothing composites the
 * result, so [draw] does nothing.
 *
 * Unlike the shipped Linux host this asks for no external-memory extensions, because nothing
 * imports the image, so it runs on a software Vulkan implementation such as lavapipe in CI.
 */
internal class HeadlessVulkanMapHost private constructor() : DesktopMapHost {

  private val rendererThread = MapRendererThread("maplibre-headless-vulkan")
  private var context: HeadlessVulkanContext? = null
  private var texture: HeadlessVulkanTexture? = null
  private var generation = 0L

  /** The extent the current texture was allocated at; the map's viewport, in logical pixels. */
  var currentExtent: DesktopMapExtent = DesktopMapExtent.Empty
    private set

  /** Frames this host handed out; a composition acquiring none never reached MapLibre at all. */
  var acquiredFrames: Int = 0
    private set

  /** Frames MapLibre actually rendered into, as opposed to acquired and skipped. */
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
    DesktopMapFrame(
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

  fun readPixel(x: Int, y: Int): RgbaPixel = rendererThread.run {
    requireNotNull(texture).readPixel(x, y)
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

  companion object {
    /**
     * Creates a host, throwing if no Vulkan implementation is usable. Deliberately not a nullable
     * "skip": a test that returns before asserting is recorded by JUnit as passed.
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

  fun graphicsQueue(): VkQueue =
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

  fun readPixel(x: Int, y: Int): RgbaPixel {
    require(x in 0..<extent.physicalWidth && y in 0..<extent.physicalHeight) {
      "Pixel ($x, $y) is outside ${extent.physicalWidth}x${extent.physicalHeight}"
    }
    val device = context.device()
    var buffer = NULL
    var bufferMemory = NULL
    var commandPool = NULL
    try {
      MemoryStack.stackPush().use { stack ->
        val bufferInfo =
          VkBufferCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
            .size(4)
            .usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT)
            .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
        val bufferOut = stack.mallocLong(1)
        checkVulkan(vkCreateBuffer(device, bufferInfo, null, bufferOut), "vkCreateBuffer")
        buffer = bufferOut[0]

        val requirements = VkMemoryRequirements.calloc(stack)
        vkGetBufferMemoryRequirements(device, buffer, requirements)
        val allocateInfo =
          VkMemoryAllocateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
            .allocationSize(requirements.size())
            .memoryTypeIndex(
              findHostVisibleMemoryType(context.physicalDevice(), requirements.memoryTypeBits())
            )
        val memoryOut = stack.mallocLong(1)
        checkVulkan(vkAllocateMemory(device, allocateInfo, null, memoryOut), "vkAllocateMemory")
        bufferMemory = memoryOut[0]
        checkVulkan(vkBindBufferMemory(device, buffer, bufferMemory, 0), "vkBindBufferMemory")

        val poolInfo =
          VkCommandPoolCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
            .queueFamilyIndex(context.handles.graphicsQueueFamilyIndex)
        val poolOut = stack.mallocLong(1)
        checkVulkan(vkCreateCommandPool(device, poolInfo, null, poolOut), "vkCreateCommandPool")
        commandPool = poolOut[0]

        val allocateCommand =
          VkCommandBufferAllocateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
            .commandPool(commandPool)
            .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
            .commandBufferCount(1)
        val commandOut = stack.mallocPointer(1)
        checkVulkan(
          vkAllocateCommandBuffers(device, allocateCommand, commandOut),
          "vkAllocateCommandBuffers",
        )
        val command = VkCommandBuffer(commandOut[0], device)
        val begin =
          VkCommandBufferBeginInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
            .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)
        checkVulkan(vkBeginCommandBuffer(command, begin), "vkBeginCommandBuffer")

        val barrier =
          VkImageMemoryBarrier.calloc(1, stack)
            .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
            .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
            .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
            .oldLayout(VK_IMAGE_LAYOUT_GENERAL)
            .newLayout(VK_IMAGE_LAYOUT_GENERAL)
            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .image(image)
        barrier
          .subresourceRange()
          .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
          .baseMipLevel(0)
          .levelCount(1)
          .baseArrayLayer(0)
          .layerCount(1)
        vkCmdPipelineBarrier(
          command,
          VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
          VK_PIPELINE_STAGE_TRANSFER_BIT,
          0,
          null,
          null,
          barrier,
        )

        val copy = VkBufferImageCopy.calloc(1, stack).bufferOffset(0)
        copy
          .imageSubresource()
          .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
          .mipLevel(0)
          .baseArrayLayer(0)
          .layerCount(1)
        copy.imageOffset().set(x, y, 0)
        copy.imageExtent().set(1, 1, 1)
        vkCmdCopyImageToBuffer(command, image, VK_IMAGE_LAYOUT_GENERAL, buffer, copy)
        checkVulkan(vkEndCommandBuffer(command), "vkEndCommandBuffer")

        val submit =
          VkSubmitInfo.calloc(1, stack)
            .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
            .pCommandBuffers(stack.pointers(command.address()))
        checkVulkan(vkQueueSubmit(context.graphicsQueue(), submit, NULL), "vkQueueSubmit")
        checkVulkan(vkQueueWaitIdle(context.graphicsQueue()), "vkQueueWaitIdle")

        val mappedOut = stack.mallocPointer(1)
        checkVulkan(vkMapMemory(device, bufferMemory, 0, 4, 0, mappedOut), "vkMapMemory")
        try {
          val bytes = MemoryUtil.memByteBuffer(mappedOut[0], 4)
          return RgbaPixel(
            red = bytes[0].toInt() and 0xff,
            green = bytes[1].toInt() and 0xff,
            blue = bytes[2].toInt() and 0xff,
            alpha = bytes[3].toInt() and 0xff,
          )
        } finally {
          vkUnmapMemory(device, bufferMemory)
        }
      }
    } finally {
      if (commandPool != NULL) vkDestroyCommandPool(device, commandPool, null)
      if (buffer != NULL) vkDestroyBuffer(device, buffer, null)
      if (bufferMemory != NULL) vkFreeMemory(device, bufferMemory, null)
    }
  }

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
          .usage(
            VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or
              VK_IMAGE_USAGE_SAMPLED_BIT or
              VK_IMAGE_USAGE_TRANSFER_SRC_BIT
          )
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

internal data class RgbaPixel(val red: Int, val green: Int, val blue: Int, val alpha: Int)

private fun findHostVisibleMemoryType(device: VkPhysicalDevice, typeBits: Int): Int {
  MemoryStack.stackPush().use { stack ->
    val properties = VkPhysicalDeviceMemoryProperties.calloc(stack)
    vkGetPhysicalDeviceMemoryProperties(device, properties)
    val required = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
    for (index in 0..<properties.memoryTypeCount()) {
      val supported = (typeBits and (1 shl index)) != 0
      val flags = properties.memoryTypes(index).propertyFlags()
      if (supported && flags and required == required) return index
    }
  }
  error("No host-visible coherent Vulkan memory type found")
}
