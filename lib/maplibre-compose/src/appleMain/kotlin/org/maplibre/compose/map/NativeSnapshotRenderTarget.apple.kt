@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package org.maplibre.compose.map

import kotlinx.cinterop.ObjCObject
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.toLong
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.render.MetalContextDescriptor
import org.maplibre.nativeffi.render.MetalOwnedTextureDescriptor
import org.maplibre.nativeffi.render.NativePointer
import org.maplibre.nativeffi.render.RenderSessionHandle
import org.maplibre.nativeffi.render.RenderTargetExtent
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.Metal.MTLDeviceProtocol

internal actual class NativeSnapshotRenderTarget
private constructor(private var device: MTLDeviceProtocol?) : AutoCloseable {
  actual fun attach(map: MapHandle, extent: MapExtent): RenderSessionHandle = withAccess {
    val current = checkNotNull(device) { "The snapshot render target is closed" }
    map.attachMetalOwnedTexture(
      MetalOwnedTextureDescriptor(
        RenderTargetExtent(extent.width, extent.height, extent.scaleFactor),
        MetalContextDescriptor(NativePointer.ofAddress(current.rawAddress())),
      )
    )
  }

  actual fun <T> withAccess(action: () -> T): T = autoreleasepool { action() }

  actual override fun close() {
    device = null
  }

  actual companion object {
    actual fun select(backends: Set<MapRenderBackend>): NativeSnapshotRenderTargetPlan? =
      if (MapRenderBackend.METAL in backends) {
        NativeSnapshotRenderTargetPlan {
          NativeSnapshotRenderTarget(
            checkNotNull(MTLCreateSystemDefaultDevice()) { "This device has no Metal GPU" }
          )
        }
      } else {
        null
      }
  }

  private fun MTLDeviceProtocol.rawAddress(): Long = (this as ObjCObject).objcPtr().toLong()
}
