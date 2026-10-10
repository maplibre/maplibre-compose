package org.maplibre.compose.map

import org.lwjgl.system.JNI
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.system.macosx.DynamicLinkLoader.RTLD_LOCAL
import org.lwjgl.system.macosx.DynamicLinkLoader.RTLD_NOW
import org.lwjgl.system.macosx.DynamicLinkLoader.dlclose
import org.lwjgl.system.macosx.DynamicLinkLoader.dlopen
import org.lwjgl.system.macosx.DynamicLinkLoader.dlsym
import org.maplibre.compose.desktop.bridge.ObjectiveC
import org.maplibre.compose.desktop.bridge.VulkanDevice
import org.maplibre.compose.interaction.internal.select
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.nativeffi.generated.MapHandle
import org.maplibre.nativeffi.generated.MetalContextDescriptor
import org.maplibre.nativeffi.generated.MetalOwnedTextureDescriptor
import org.maplibre.nativeffi.generated.OpenglOwnedTextureDescriptor
import org.maplibre.nativeffi.generated.RenderSessionAttachOptions
import org.maplibre.nativeffi.generated.RenderSessionAttachment
import org.maplibre.nativeffi.generated.VulkanOwnedTextureDescriptor
import org.maplibre.nativeffi.render.NativePointer

internal actual class NativeSnapshotRenderTarget
private constructor(private val delegate: Delegate) : AutoCloseable {
  actual fun attach(
    map: MapHandle,
    extent: MapExtent,
    options: RenderSessionAttachOptions,
  ): RenderSessionAttachment = delegate.attach(map, extent, options)

  actual override fun close() = delegate.close()

  actual companion object {
    actual fun select(backends: Set<MapRenderBackend>): NativeSnapshotRenderTargetPlan? =
      select(System.getProperty("os.name").orEmpty().lowercase(), backends)

    internal fun select(
      os: String,
      backends: Set<MapRenderBackend>,
    ): NativeSnapshotRenderTargetPlan? =
      when {
        os.contains("mac") && MapRenderBackend.Metal in backends ->
          NativeSnapshotRenderTargetPlan {
            NativeSnapshotRenderTarget(MetalDelegate.create())
          }
        MapRenderBackend.Vulkan in backends ->
          NativeSnapshotRenderTargetPlan {
            NativeSnapshotRenderTarget(VulkanDelegate(VulkanDevice.create()))
          }
        MapRenderBackend.OpenGl in backends ->
          DesktopOpenGlSnapshotContext.provider(os)?.let { createContext ->
            NativeSnapshotRenderTargetPlan {
              NativeSnapshotRenderTarget(OpenGlDelegate(createContext()))
            }
          }
        else -> null
      }
  }

  private interface Delegate : AutoCloseable {
    fun attach(
      map: MapHandle,
      extent: MapExtent,
      options: RenderSessionAttachOptions,
    ): RenderSessionAttachment
  }

  private class MetalDelegate(private var framework: Long, private var device: Long) : Delegate {
    override fun attach(
      map: MapHandle,
      extent: MapExtent,
      options: RenderSessionAttachOptions,
    ): RenderSessionAttachment = ObjectiveC.runInAutoreleasePool {
      check(device != NULL) { "The Metal snapshot target is closed" }
      map.attachMetalOwnedTexture(
        MetalOwnedTextureDescriptor(
          extent.toFfiExtent(),
          MetalContextDescriptor(NativePointer.ofAddress(device)),
        ),
        options,
      )
    }

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

  private class VulkanDelegate(private val context: VulkanDevice) : Delegate {
    override fun attach(
      map: MapHandle,
      extent: MapExtent,
      options: RenderSessionAttachOptions,
    ): RenderSessionAttachment =
      map.attachVulkanOwnedTexture(
        VulkanOwnedTextureDescriptor(extent.toFfiExtent(), context.handles.toFfi()),
        options,
      )

    override fun close() = context.close()
  }

  private class OpenGlDelegate(private val context: DesktopOpenGlSnapshotContext) : Delegate {
    override fun attach(
      map: MapHandle,
      extent: MapExtent,
      options: RenderSessionAttachOptions,
    ): RenderSessionAttachment =
      map.attachOpenglOwnedTexture(
        OpenglOwnedTextureDescriptor(extent.toFfiExtent(), context.descriptor),
        options,
      )

    override fun close() = context.close()
  }
}
