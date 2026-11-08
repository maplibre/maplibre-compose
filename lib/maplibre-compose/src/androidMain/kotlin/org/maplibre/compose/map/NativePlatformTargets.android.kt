package org.maplibre.compose.map

import org.maplibre.compose.mlnffi.AndroidSurfaceTarget
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.nativeffi.Completion
import org.maplibre.nativeffi.MapHandle
import org.maplibre.nativeffi.RenderSessionAttachOptions
import org.maplibre.nativeffi.RenderSessionHandle
import org.maplibre.nativeffi.attachAndroidSurface
import org.maplibre.nativeffi.setAndroidSurfaceTarget

internal actual fun attachPlatformTarget(
  map: MapHandle,
  target: MlnFfiRenderTarget,
  extent: MapExtent,
  options: RenderSessionAttachOptions,
): RenderSessionHandle =
  when (target) {
    is AndroidSurfaceTarget ->
      map.attachAndroidSurface(target.surface, extent.toLogicalSize(), options = options)
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

internal actual fun initializePlatformGraphics(backends: Set<MapRenderBackend>) {}
