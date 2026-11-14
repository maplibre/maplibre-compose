package org.maplibre.compose.map

import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MetalSurfaceTarget
import org.maplibre.compose.mlnffi.MetalTextureTarget
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.compose.mlnffi.NativeHandle
import org.maplibre.nativeffi.Completion
import org.maplibre.nativeffi.GpuDeviceHandle
import org.maplibre.nativeffi.MapHandle
import org.maplibre.nativeffi.MetalDeviceDescriptor
import org.maplibre.nativeffi.NativePointer
import org.maplibre.nativeffi.RenderSessionAttachOptions
import org.maplibre.nativeffi.RenderSessionHandle
import org.maplibre.nativeffi.wrapMetal

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

internal actual fun initializePlatformGraphics(backends: Set<MapRenderBackend>) {}
