package org.maplibre.compose.map

import org.maplibre.compose.mlnffi.AndroidSurfaceTarget
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.compose.mlnffi.VulkanImageTarget
import org.maplibre.nativeffi.Completion
import org.maplibre.nativeffi.GpuDeviceHandle
import org.maplibre.nativeffi.GpuDeviceOptions
import org.maplibre.nativeffi.MapHandle
import org.maplibre.nativeffi.RenderBackend
import org.maplibre.nativeffi.RenderSessionAttachOptions
import org.maplibre.nativeffi.RenderSessionHandle
import org.maplibre.nativeffi.VulkanDeviceDescriptor
import org.maplibre.nativeffi.attachAndroidSurface
import org.maplibre.nativeffi.setAndroidSurfaceTarget
import org.maplibre.nativeffi.wrapVulkan

internal actual fun attachPlatformTarget(
  map: MapHandle,
  target: MlnFfiRenderTarget,
  extent: MapExtent,
  device: GpuDeviceHandle?,
  options: RenderSessionAttachOptions,
): RenderSessionHandle =
  when (target) {
    is AndroidSurfaceTarget ->
      map.attachAndroidSurface(target.surface, extent.toLogicalSize(), device, options)
    else -> error("Unsupported platform target: $target")
  }

internal actual fun replacePlatformTarget(
  session: RenderSessionHandle,
  target: MlnFfiRenderTarget,
  extent: MapExtent,
): Completion<*> =
  when (target) {
    is AndroidSurfaceTarget ->
      session.setAndroidSurfaceTarget(target.surface, extent.toLogicalSize())
    else -> error("Unsupported platform target: $target")
  }

internal actual fun wrapPlatformDevice(target: MlnFfiRenderTarget): GpuDeviceHandle? =
  when (target) {
    // One runtime carries both backends, so the device picks the one the map renders with.
    is AndroidSurfaceTarget ->
      GpuDeviceHandle.create(
        GpuDeviceOptions(
          backend =
            when (target.backend) {
              MapRenderBackend.Vulkan -> RenderBackend.VULKAN
              MapRenderBackend.OpenGl -> RenderBackend.OPEN_GL
              MapRenderBackend.Metal -> RenderBackend.METAL
            },
        ),
      )
    is VulkanImageTarget ->
      GpuDeviceHandle.wrapVulkan(VulkanDeviceDescriptor(context = target.context.toFfi()))
    else -> null
  }

internal actual fun initializePlatformGraphics(backends: Set<MapRenderBackend>) {}
