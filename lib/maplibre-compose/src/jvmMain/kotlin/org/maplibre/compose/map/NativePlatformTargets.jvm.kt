package org.maplibre.compose.map

import org.maplibre.compose.desktop.bridge.MacAngleLibraries
import org.maplibre.compose.desktop.bridge.ensureVulkanFunctionProvider
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MetalSurfaceTarget
import org.maplibre.compose.mlnffi.MetalTextureTarget
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.compose.mlnffi.NativeHandle
import org.maplibre.compose.mlnffi.VulkanImageTarget
import org.maplibre.nativeffi.Completion
import org.maplibre.nativeffi.GpuDeviceHandle
import org.maplibre.nativeffi.MapHandle
import org.maplibre.nativeffi.MetalDeviceDescriptor
import org.maplibre.nativeffi.NativePointer
import org.maplibre.nativeffi.RenderSessionAttachOptions
import org.maplibre.nativeffi.RenderSessionHandle
import org.maplibre.nativeffi.VulkanDeviceDescriptor
import org.maplibre.nativeffi.wrapMetal
import org.maplibre.nativeffi.wrapVulkan

internal actual fun attachPlatformTarget(
  map: MapHandle,
  target: MlnFfiRenderTarget,
  extent: MapExtent,
  device: GpuDeviceHandle?,
  options: RenderSessionAttachOptions,
): RenderSessionHandle = error("Unsupported platform target: $target")

internal actual fun replacePlatformTarget(
  session: RenderSessionHandle,
  target: MlnFfiRenderTarget,
  extent: MapExtent,
): Completion<*> = error("Unsupported platform target: $target")

internal actual fun wrapPlatformDevice(target: MlnFfiRenderTarget): GpuDeviceHandle? =
  when (target) {
    // The session submits to the host's queue from the renderer thread, which also submits the
    // host's own work, so the two never submit at once and need no queue lock.
    is VulkanImageTarget ->
      GpuDeviceHandle.wrapVulkan(VulkanDeviceDescriptor(context = target.context.toFfi()))
    is MetalTextureTarget -> wrapMetalDevice(target.device)
    is MetalSurfaceTarget -> target.device.takeUnless { it.isNull }?.let(::wrapMetalDevice)
    else -> null
  }

// The session renders on a queue of its own, and its frames are complete when acquired.
private fun wrapMetalDevice(device: NativeHandle) =
  GpuDeviceHandle.wrapMetal(
    MetalDeviceDescriptor(
      device = NativePointer.ofAddress(device.address),
      commandQueue = NativePointer.NULL_POINTER,
    ),
  )

internal actual fun initializePlatformGraphics(backends: Set<MapRenderBackend>) {
  if (System.getProperty("os.name").startsWith("Mac")) {
    if (MapRenderBackend.OpenGl in backends) MacAngleLibraries.load()
    if (MapRenderBackend.Vulkan in backends) ensureVulkanFunctionProvider()
  }
}
