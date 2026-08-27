package org.maplibre.compose.mlnffi

import org.lwjgl.system.JNI
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.system.macosx.DynamicLinkLoader.RTLD_LOCAL
import org.lwjgl.system.macosx.DynamicLinkLoader.RTLD_NOW
import org.lwjgl.system.macosx.DynamicLinkLoader.dlclose
import org.lwjgl.system.macosx.DynamicLinkLoader.dlopen
import org.lwjgl.system.macosx.DynamicLinkLoader.dlsym
import org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR
import org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME
import org.lwjgl.vulkan.KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO
import org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO
import org.lwjgl.vulkan.VK10.vkCreateDevice
import org.lwjgl.vulkan.VK10.vkCreateInstance
import org.lwjgl.vulkan.VK10.vkDestroyDevice
import org.lwjgl.vulkan.VK10.vkDestroyInstance
import org.lwjgl.vulkan.VK10.vkDeviceWaitIdle
import org.lwjgl.vulkan.VK10.vkEnumeratePhysicalDevices
import org.lwjgl.vulkan.VK10.vkGetDeviceQueue
import org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1
import org.lwjgl.vulkan.VkApplicationInfo
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkDeviceCreateInfo
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo
import org.lwjgl.vulkan.VkInstance
import org.lwjgl.vulkan.VkInstanceCreateInfo
import org.lwjgl.vulkan.VkPhysicalDevice
import org.lwjgl.vulkan.VkQueue
import org.maplibre.compose.desktop.bridge.ObjectiveC
import org.maplibre.compose.desktop.bridge.checkVulkan
import org.maplibre.compose.desktop.bridge.ensureVulkanFunctionProvider
import org.maplibre.compose.desktop.bridge.findVulkanGraphicsQueueFamily
import org.maplibre.compose.desktop.bridge.vulkanDeviceExtensions
import org.maplibre.compose.desktop.bridge.vulkanFunctionAddress
import org.maplibre.compose.desktop.bridge.vulkanInstanceExtensions
import org.maplibre.compose.desktop.bridge.vulkanStringBuffer
import org.maplibre.nativeffi.Maplibre
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.render.MetalContextDescriptor
import org.maplibre.nativeffi.render.MetalOwnedTextureDescriptor
import org.maplibre.nativeffi.render.NativePointer
import org.maplibre.nativeffi.render.RenderBackend
import org.maplibre.nativeffi.render.RenderSessionHandle
import org.maplibre.nativeffi.render.RenderTargetExtent
import org.maplibre.nativeffi.render.VulkanContextDescriptor
import org.maplibre.nativeffi.render.VulkanOwnedTextureDescriptor

internal actual fun createSnapshotTarget(): MlnFfiSnapshotTarget {
  val backends = Maplibre.supportedRenderBackends()
  return when {
    RenderBackend.VULKAN in backends -> JvmVulkanSnapshotTarget()
    RenderBackend.METAL in backends -> JvmMetalSnapshotTarget()
    else ->
      throw UnsupportedOperationException(
        "MapState.snapshot has no still-image path for the packaged desktop runtime " +
          "(${backends.joinToString().ifEmpty { "none" }}); package the Vulkan or Metal runtime"
      )
  }
}

/** The system Metal device, since a texture session requires a non-null device. */
private class JvmMetalSnapshotTarget : MlnFfiSnapshotTarget {
  override val backend: MapRenderBackend = MapRenderBackend.METAL

  private var framework: Long = NULL
  private var device: Long = NULL

  init {
    try {
      framework = dlopen("/System/Library/Frameworks/Metal.framework/Metal", RTLD_NOW or RTLD_LOCAL)
      check(framework != NULL) { "Could not load Metal.framework" }
      val factory = dlsym(framework, "MTLCreateSystemDefaultDevice")
      check(factory != NULL) { "MTLCreateSystemDefaultDevice was not found" }
      val borrowedDevice = JNI.invokeP(factory)
      check(borrowedDevice != NULL) { "macOS has no system Metal device" }
      device = ObjectiveC.sendPointer(borrowedDevice, "retain")
    } catch (error: Throwable) {
      close()
      throw error
    }
  }

  override fun attach(map: MapHandle, extent: RenderTargetExtent): RenderSessionHandle =
    map.attachMetalOwnedTexture(
      MetalOwnedTextureDescriptor(
        extent = extent,
        context = MetalContextDescriptor(device = NativePointer.ofAddress(device)),
      )
    )

  override fun close() {
    if (device != NULL) {
      ObjectiveC.release(device)
      device = NULL
    }
    if (framework != NULL) {
      dlclose(framework)
      framework = NULL
    }
  }
}

/** A Vulkan instance and device of the snapshot's own, since no Compose host supplies one. */
private class JvmVulkanSnapshotTarget : MlnFfiSnapshotTarget {
  override val backend: MapRenderBackend = MapRenderBackend.VULKAN

  private var instance: VkInstance? = null
  private var physicalDevice: VkPhysicalDevice? = null
  private var device: VkDevice? = null
  private var graphicsQueue: VkQueue? = null
  private var graphicsQueueFamilyIndex = 0

  init {
    try {
      createContext()
    } catch (error: Throwable) {
      close()
      throw error
    }
  }

  override fun attach(map: MapHandle, extent: RenderTargetExtent): RenderSessionHandle =
    map.attachVulkanOwnedTexture(
      VulkanOwnedTextureDescriptor(
        extent = extent,
        context =
          VulkanContextDescriptor(
            instance = NativePointer.ofAddress(checkNotNull(instance).address()),
            physicalDevice = NativePointer.ofAddress(checkNotNull(physicalDevice).address()),
            device = NativePointer.ofAddress(checkNotNull(device).address()),
            graphicsQueue = NativePointer.ofAddress(checkNotNull(graphicsQueue).address()),
            graphicsQueueFamilyIndex = graphicsQueueFamilyIndex,
            getInstanceProcAddr =
              NativePointer.ofAddress(vulkanFunctionAddress("vkGetInstanceProcAddr")),
            getDeviceProcAddr =
              NativePointer.ofAddress(vulkanFunctionAddress("vkGetDeviceProcAddr")),
          ),
      )
    )

  private fun createContext() {
    ensureVulkanFunctionProvider()
    MemoryStack.stackPush().use { stack ->
      val available = stack.vulkanInstanceExtensions()
      val enablePortability = VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME in available
      val instanceExtensions =
        if (enablePortability) setOf(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME) else emptySet()
      val app =
        VkApplicationInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
          .pApplicationName(stack.UTF8("maplibre-compose-snapshot"))
          .pEngineName(stack.UTF8("maplibre-native-ffi"))
          .apiVersion(VK_API_VERSION_1_1)
      val createInfo =
        VkInstanceCreateInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
          .pApplicationInfo(app)
          .ppEnabledExtensionNames(stack.vulkanStringBuffer(instanceExtensions))
      if (enablePortability) createInfo.flags(VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR)
      val instanceOut = stack.mallocPointer(1)
      checkVulkan(vkCreateInstance(createInfo, null, instanceOut), "vkCreateInstance")
      val created = VkInstance(instanceOut[0], createInfo)
      instance = created

      val count = stack.mallocInt(1)
      checkVulkan(
        vkEnumeratePhysicalDevices(created, count, null),
        "vkEnumeratePhysicalDevices(count)",
      )
      check(count[0] != 0) { "No Vulkan physical devices found" }
      val devices = stack.mallocPointer(count[0])
      checkVulkan(vkEnumeratePhysicalDevices(created, count, devices), "vkEnumeratePhysicalDevices")
      for (index in 0..<devices.capacity()) {
        val candidate = VkPhysicalDevice(devices[index], created)
        val queueFamily = stack.findVulkanGraphicsQueueFamily(candidate)
        if (queueFamily >= 0) {
          physicalDevice = candidate
          graphicsQueueFamilyIndex = queueFamily
          break
        }
      }
      val physical = checkNotNull(physicalDevice) { "No Vulkan device supports graphics" }

      // The portability-subset extension must be enabled on a device that advertises it.
      val deviceExtensions =
        if (VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME in stack.vulkanDeviceExtensions(physical)) {
          setOf(VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME)
        } else {
          emptySet()
        }
      val priorities = stack.floats(1.0f)
      val queueInfo =
        VkDeviceQueueCreateInfo.calloc(1, stack)
          .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
          .queueFamilyIndex(graphicsQueueFamilyIndex)
          .pQueuePriorities(priorities)
      val deviceInfo =
        VkDeviceCreateInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
          .pQueueCreateInfos(queueInfo)
          .ppEnabledExtensionNames(stack.vulkanStringBuffer(deviceExtensions))
      val deviceOut = stack.mallocPointer(1)
      checkVulkan(vkCreateDevice(physical, deviceInfo, null, deviceOut), "vkCreateDevice")
      val createdDevice = VkDevice(deviceOut[0], physical, deviceInfo)
      device = createdDevice
      val queueOut = stack.mallocPointer(1)
      vkGetDeviceQueue(createdDevice, graphicsQueueFamilyIndex, 0, queueOut)
      graphicsQueue = VkQueue(queueOut[0], createdDevice)
    }
  }

  override fun close() {
    device?.let {
      vkDeviceWaitIdle(it)
      vkDestroyDevice(it, null)
      device = null
    }
    graphicsQueue = null
    physicalDevice = null
    instance?.let {
      vkDestroyInstance(it, null)
      instance = null
    }
  }
}
