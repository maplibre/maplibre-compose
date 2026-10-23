@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package org.maplibre.compose.map

import kotlinx.cinterop.ObjCObject
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.toLong
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.nativeffi.generated.MapHandle
import org.maplibre.nativeffi.generated.MetalContextDescriptor
import org.maplibre.nativeffi.generated.MetalOwnedTextureDescriptor
import org.maplibre.nativeffi.generated.RenderSessionAttachOptions
import org.maplibre.nativeffi.generated.RenderSessionAttachment
import org.maplibre.nativeffi.render.NativePointer
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.Metal.MTLDeviceProtocol

internal actual class NativeSnapshotRenderTarget
private constructor(private var device: MTLDeviceProtocol?) : AutoCloseable {
  actual fun attach(
    map: MapHandle,
    extent: MapExtent,
    options: RenderSessionAttachOptions,
  ): RenderSessionAttachment = autoreleasepool {
    val current = checkNotNull(device) { "The snapshot render target is closed" }
    map.attachMetalOwnedTexture(
      MetalOwnedTextureDescriptor(
        extent.toFfiExtent(),
        MetalContextDescriptor(NativePointer.ofAddress(current.rawAddress())),
      ),
      options,
    )
  }

  actual override fun close() {
    device = null
  }

  actual companion object {
    actual fun select(backends: Set<MapRenderBackend>): NativeSnapshotRenderTargetPlan? =
      if (MapRenderBackend.Metal in backends) {
        NativeSnapshotRenderTargetPlan {
          NativeSnapshotRenderTarget(
            checkNotNull(MTLCreateSystemDefaultDevice()) { "This device has no Metal GPU" },
          )
        }
      } else {
        null
      }
  }

  private fun MTLDeviceProtocol.rawAddress(): Long = (this as ObjCObject).objcPtr().toLong()
}
