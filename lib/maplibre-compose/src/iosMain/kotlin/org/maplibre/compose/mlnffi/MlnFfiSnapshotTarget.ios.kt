package org.maplibre.compose.mlnffi

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.objcPtr
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.render.MetalContextDescriptor
import org.maplibre.nativeffi.render.MetalOwnedTextureDescriptor
import org.maplibre.nativeffi.render.NativePointer
import org.maplibre.nativeffi.render.RenderSessionHandle
import org.maplibre.nativeffi.render.RenderTargetExtent
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.Metal.MTLDeviceProtocol

internal actual fun createSnapshotTarget(): MlnFfiSnapshotTarget = IosMetalSnapshotTarget()

/** The system Metal device, since a texture session requires a non-null device. */
private class IosMetalSnapshotTarget : MlnFfiSnapshotTarget {
  override val backend: MapRenderBackend = MapRenderBackend.METAL

  // Held for the target's lifetime so ARC keeps the device alive while the session uses it.
  private var device: MTLDeviceProtocol? =
    checkNotNull(MTLCreateSystemDefaultDevice()) { "iOS has no system Metal device" }

  @OptIn(ExperimentalForeignApi::class)
  override fun attach(map: MapHandle, extent: RenderTargetExtent): RenderSessionHandle {
    val live = checkNotNull(device) { "The snapshot target is closed" }
    return map.attachMetalOwnedTexture(
      MetalOwnedTextureDescriptor(
        extent = extent,
        context = MetalContextDescriptor(device = NativePointer.ofAddress(live.objcPtr().toLong())),
      )
    )
  }

  override fun close() {
    device = null
  }
}
