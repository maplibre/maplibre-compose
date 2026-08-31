package org.maplibre.compose.map

import org.lwjgl.system.JNI
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.system.macosx.DynamicLinkLoader.RTLD_LOCAL
import org.lwjgl.system.macosx.DynamicLinkLoader.RTLD_NOW
import org.lwjgl.system.macosx.DynamicLinkLoader.dlclose
import org.lwjgl.system.macosx.DynamicLinkLoader.dlopen
import org.lwjgl.system.macosx.DynamicLinkLoader.dlsym
import org.maplibre.compose.desktop.bridge.DesktopVulkanContext
import org.maplibre.compose.desktop.bridge.ObjectiveC
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.VulkanContextHandles
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.render.MetalContextDescriptor
import org.maplibre.nativeffi.render.MetalOwnedTextureDescriptor
import org.maplibre.nativeffi.render.NativePointer
import org.maplibre.nativeffi.render.OpenGLOwnedTextureDescriptor
import org.maplibre.nativeffi.render.RenderSessionHandle
import org.maplibre.nativeffi.render.RenderTargetExtent
import org.maplibre.nativeffi.render.VulkanContextDescriptor
import org.maplibre.nativeffi.render.VulkanOwnedTextureDescriptor

internal actual class NativeSnapshotRenderTarget
private constructor(private val delegate: Delegate) : AutoCloseable {
  actual fun attach(map: MapHandle, extent: MapExtent): RenderSessionHandle =
    delegate.attach(map, extent)

  actual fun <T> withAccess(action: () -> T): T = delegate.withAccess(action)

  actual override fun close() = delegate.close()

  actual companion object {
    actual fun create(backends: Set<MapRenderBackend>): NativeSnapshotRenderTarget {
      val os = System.getProperty("os.name").orEmpty().lowercase()
      val delegate =
        when {
          os.contains("mac") && MapRenderBackend.METAL in backends -> MetalDelegate.create()
          MapRenderBackend.VULKAN in backends ->
            VulkanDelegate(DesktopVulkanContext.createOffscreen())
          MapRenderBackend.OPENGL in backends ->
            OpenGlDelegate(DesktopOpenGlSnapshotContext.create(os))
          else ->
            throw UnsupportedOperationException(
              "No offscreen snapshot backend is available for $os from ${backends.joinToString()}"
            )
        }
      return NativeSnapshotRenderTarget(delegate)
    }
  }

  private interface Delegate : AutoCloseable {
    fun attach(map: MapHandle, extent: MapExtent): RenderSessionHandle

    fun <T> withAccess(action: () -> T): T = action()
  }

  private class MetalDelegate(private var framework: Long, private var device: Long) : Delegate {
    override fun attach(map: MapHandle, extent: MapExtent): RenderSessionHandle = withAccess {
      check(device != NULL) { "The Metal snapshot target is closed" }
      map.attachMetalOwnedTexture(
        MetalOwnedTextureDescriptor(
          extent.toSnapshotRenderTargetExtent(),
          MetalContextDescriptor(NativePointer.ofAddress(device)),
        )
      )
    }

    override fun <T> withAccess(action: () -> T): T = ObjectiveC.runInAutoreleasePool(action)

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

    companion object {
      fun create(): MetalDelegate {
        val framework =
          dlopen("/System/Library/Frameworks/Metal.framework/Metal", RTLD_NOW or RTLD_LOCAL)
        check(framework != NULL) { "Could not load Metal.framework" }
        try {
          val factory = dlsym(framework, "MTLCreateSystemDefaultDevice")
          check(factory != NULL) { "MTLCreateSystemDefaultDevice was not found" }
          val borrowed = JNI.invokeP(factory)
          check(borrowed != NULL) { "This machine has no Metal GPU" }
          return MetalDelegate(framework, ObjectiveC.sendPointer(borrowed, "retain"))
        } catch (error: Throwable) {
          dlclose(framework)
          throw error
        }
      }
    }
  }

  private class VulkanDelegate(private val context: DesktopVulkanContext) : Delegate {
    override fun attach(map: MapHandle, extent: MapExtent): RenderSessionHandle =
      map.attachVulkanOwnedTexture(
        VulkanOwnedTextureDescriptor(
          extent.toSnapshotRenderTargetExtent(),
          context.handles.toSnapshotVulkanContextDescriptor(),
        )
      )

    override fun close() = context.close()
  }

  private class OpenGlDelegate(private val context: DesktopOpenGlSnapshotContext) : Delegate {
    override fun attach(map: MapHandle, extent: MapExtent): RenderSessionHandle =
      map.attachOpenGLOwnedTexture(
        OpenGLOwnedTextureDescriptor(extent.toSnapshotRenderTargetExtent(), context.descriptor)
      )

    override fun close() = context.close()
  }
}

private fun MapExtent.toSnapshotRenderTargetExtent() =
  RenderTargetExtent(width, height, scaleFactor)

private fun VulkanContextHandles.toSnapshotVulkanContextDescriptor() =
  VulkanContextDescriptor(
    instance = NativePointer.ofAddress(instance.address),
    physicalDevice = NativePointer.ofAddress(physicalDevice.address),
    device = NativePointer.ofAddress(device.address),
    graphicsQueue = NativePointer.ofAddress(graphicsQueue.address),
    graphicsQueueFamilyIndex = graphicsQueueFamilyIndex,
    getInstanceProcAddr = NativePointer.ofAddress(getInstanceProcAddr.address),
    getDeviceProcAddr = NativePointer.ofAddress(getDeviceProcAddr.address),
  )
