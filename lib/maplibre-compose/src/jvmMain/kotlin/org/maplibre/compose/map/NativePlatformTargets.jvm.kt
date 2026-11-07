package org.maplibre.compose.map

import org.maplibre.compose.desktop.bridge.MacAngleLibraries
import org.maplibre.compose.desktop.bridge.ensureVulkanFunctionProvider
import org.maplibre.compose.mlnffi.DxgiSharedTextureRingTarget
import org.maplibre.compose.mlnffi.IoSurfaceRingTarget
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MetalSurfaceTarget
import org.maplibre.compose.mlnffi.MetalTextureTarget
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.compose.mlnffi.VulkanImageTarget
import org.maplibre.nativeffi.Completion
import org.maplibre.nativeffi.DxgiSharedTextureRingDescriptor
import org.maplibre.nativeffi.IoSurfaceRingDescriptor
import org.maplibre.nativeffi.MapHandle
import org.maplibre.nativeffi.MetalContextDescriptor
import org.maplibre.nativeffi.NativePointer
import org.maplibre.nativeffi.RenderSessionAttachOptions
import org.maplibre.nativeffi.RenderSessionHandle
import org.maplibre.nativeffi.attachDxgiSharedTextureRing
import org.maplibre.nativeffi.attachIoSurfaceRing
import org.maplibre.nativeffi.attachMetalBorrowedTexture
import org.maplibre.nativeffi.attachMetalSurface
import org.maplibre.nativeffi.attachVulkanBorrowedTexture
import org.maplibre.nativeffi.setMetalBorrowedTextureTarget
import org.maplibre.nativeffi.setMetalSurfaceTarget
import org.maplibre.nativeffi.setVulkanBorrowedTextureTarget

internal actual fun attachPlatformTarget(
  map: MapHandle,
  target: MlnFfiRenderTarget,
  extent: MapExtent,
  options: RenderSessionAttachOptions,
): RenderSessionHandle =
  when (target) {
    is VulkanImageTarget -> map.attachVulkanBorrowedTexture(target.toDescriptor(extent), options)
    is MetalTextureTarget -> map.attachMetalBorrowedTexture(target.toDescriptor(extent), options)
    is MetalSurfaceTarget -> map.attachMetalSurface(target.toDescriptor(extent), options)
    is IoSurfaceRingTarget ->
      map.attachIoSurfaceRing(
        IoSurfaceRingDescriptor(
          logicalSize = extent.toLogicalSize(),
          bufferCount = target.bufferCount.toUInt(),
          context = MetalContextDescriptor(NativePointer.ofAddress(target.device.address)),
        ),
        options,
      )
    is DxgiSharedTextureRingTarget ->
      map.attachDxgiSharedTextureRing(
        DxgiSharedTextureRingDescriptor(
          logicalSize = extent.toLogicalSize(),
          textureCount = target.textureCount.toUInt(),
        ),
        target.device,
        options,
      )
    else -> error("Unsupported platform target: $target")
  }

internal actual fun replacePlatformTarget(
  session: RenderSessionHandle,
  target: MlnFfiRenderTarget,
  extent: MapExtent,
): Completion<*> =
  when (target) {
    is VulkanImageTarget -> session.setVulkanBorrowedTextureTarget(target.toReplacement(extent))
    is MetalTextureTarget -> session.setMetalBorrowedTextureTarget(target.toReplacement(extent))
    is MetalSurfaceTarget -> session.setMetalSurfaceTarget(target.toReplacement(extent))
    // The session reallocates its own ring at the new size.
    is IoSurfaceRingTarget,
    is DxgiSharedTextureRingTarget -> session.resize(extent.toLogicalSize())
    else -> error("Unsupported platform target: $target")
  }

internal actual fun initializePlatformGraphics(backends: Set<MapRenderBackend>) {
  if (System.getProperty("os.name").startsWith("Mac")) {
    if (MapRenderBackend.OpenGl in backends) MacAngleLibraries.load()
    if (MapRenderBackend.Vulkan in backends) ensureVulkanFunctionProvider()
  }
}
