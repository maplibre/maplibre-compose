package org.maplibre.compose.map

import kotlinx.coroutines.Deferred
import org.maplibre.compose.desktop.bridge.MacAngleLibraries
import org.maplibre.compose.desktop.bridge.ensureVulkanFunctionProvider
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MetalSurfaceTarget
import org.maplibre.compose.mlnffi.MetalTextureTarget
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.compose.mlnffi.VulkanImageTarget
import org.maplibre.compose.mlnffi.VulkanSurfaceTarget
import org.maplibre.nativeffi.MapHandle
import org.maplibre.nativeffi.NewRenderSession
import org.maplibre.nativeffi.RenderSessionAttachOptions
import org.maplibre.nativeffi.RenderSessionHandle
import org.maplibre.nativeffi.attachMetalBorrowedTexture
import org.maplibre.nativeffi.attachMetalSurface
import org.maplibre.nativeffi.attachVulkanBorrowedTexture
import org.maplibre.nativeffi.attachVulkanSurface
import org.maplibre.nativeffi.setMetalBorrowedTextureTarget
import org.maplibre.nativeffi.setMetalSurfaceTarget
import org.maplibre.nativeffi.setVulkanBorrowedTextureTarget

internal actual fun attachPlatformTarget(
  map: MapHandle,
  target: MlnFfiRenderTarget,
  extent: MapExtent,
  options: RenderSessionAttachOptions,
): NewRenderSession =
  when (target) {
    is VulkanImageTarget -> map.attachVulkanBorrowedTexture(target.toDescriptor(extent), options)
    is VulkanSurfaceTarget -> map.attachVulkanSurface(target.toDescriptor(extent), options)
    is MetalTextureTarget -> map.attachMetalBorrowedTexture(target.toDescriptor(extent), options)
    is MetalSurfaceTarget -> map.attachMetalSurface(target.toDescriptor(extent), options)
    else -> error("Unsupported platform target: $target")
  }

internal actual fun replacePlatformTarget(
  session: RenderSessionHandle,
  target: MlnFfiRenderTarget,
  extent: MapExtent,
): Deferred<*> =
  when (target) {
    is VulkanImageTarget -> session.setVulkanBorrowedTextureTarget(target.toDescriptor(extent))
    is VulkanSurfaceTarget -> session.resize(extent.toFfiExtent())
    is MetalTextureTarget -> session.setMetalBorrowedTextureTarget(target.toDescriptor(extent))
    is MetalSurfaceTarget -> session.setMetalSurfaceTarget(target.toDescriptor(extent))
    else -> error("Unsupported platform target: $target")
  }

internal actual fun initializePlatformGraphics(backends: Set<MapRenderBackend>) {
  if (System.getProperty("os.name").startsWith("Mac")) {
    if (MapRenderBackend.OpenGl in backends) MacAngleLibraries.load()
    if (MapRenderBackend.Vulkan in backends) ensureVulkanFunctionProvider()
  }
}
