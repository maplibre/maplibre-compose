package org.maplibre.compose.map

import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MetalSurfaceTarget
import org.maplibre.compose.mlnffi.MetalTextureTarget
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.nativeffi.Completion
import org.maplibre.nativeffi.MapHandle
import org.maplibre.nativeffi.RenderSessionAttachOptions
import org.maplibre.nativeffi.RenderSessionHandle
import org.maplibre.nativeffi.attachMetalBorrowedTexture
import org.maplibre.nativeffi.attachMetalSurface
import org.maplibre.nativeffi.setMetalBorrowedTextureTarget
import org.maplibre.nativeffi.setMetalSurfaceTarget

internal actual fun attachPlatformTarget(
  map: MapHandle,
  target: MlnFfiRenderTarget,
  extent: MapExtent,
  options: RenderSessionAttachOptions,
): RenderSessionHandle =
  when (target) {
    is MetalTextureTarget -> map.attachMetalBorrowedTexture(target.toDescriptor(extent), options)
    is MetalSurfaceTarget -> map.attachMetalSurface(target.toDescriptor(extent), options)
    else -> error("Unsupported platform target: $target")
  }

internal actual fun replacePlatformTarget(
  session: RenderSessionHandle,
  target: MlnFfiRenderTarget,
  extent: MapExtent,
): Completion<*> =
  when (target) {
    is MetalTextureTarget -> session.setMetalBorrowedTextureTarget(target.toReplacement(extent))
    is MetalSurfaceTarget -> session.setMetalSurfaceTarget(target.toReplacement(extent))
    else -> error("Unsupported platform target: $target")
  }

internal actual fun initializePlatformGraphics(backends: Set<MapRenderBackend>) {}
