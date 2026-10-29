package org.maplibre.compose.map

import kotlinx.coroutines.Deferred
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.compose.mlnffi.VulkanImageTarget
import org.maplibre.compose.mlnffi.VulkanSurfaceTarget
import org.maplibre.nativeffi.MapHandle
import org.maplibre.nativeffi.NewRenderSession
import org.maplibre.nativeffi.RenderSessionAttachOptions
import org.maplibre.nativeffi.RenderSessionHandle
import org.maplibre.nativeffi.attachVulkanBorrowedTexture
import org.maplibre.nativeffi.attachVulkanSurface
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
    else -> error("Unsupported platform target: $target")
  }

internal actual fun initializePlatformGraphics(backends: Set<MapRenderBackend>) {}
