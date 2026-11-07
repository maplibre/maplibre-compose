@file:OptIn(org.maplibre.compose.util.ExperimentalMaplibreComposeApi::class)

package org.maplibre.compose.desktop.bridge

import org.maplibre.compose.desktop.MetalComposeGpuContext
import org.maplibre.compose.desktop.MetalPresentationHost
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.IoSurfaceRingTarget
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MetalTextureTarget
import org.maplibre.compose.mlnffi.NativeHandle
import org.maplibre.compose.mlnffi.RenderBackendPair
import org.maplibre.compose.mlnffi.TextureOrigin
import org.maplibre.nativeffi.AcquiredFrameHandle
import org.maplibre.nativeffi.withIoSurface

/**
 * Bridges MapLibre's Metal rendering into Compose's Metal context. The session renders on Compose's
 * `MTLDevice` into a ring of `IOSurface`s it allocates, and the host wraps each surface as an
 * `MTLTexture` on the same device for Skia to draw.
 */
internal class IoSurfaceRingMapHost(presentationHost: MetalPresentationHost) :
  SessionRingMapHost<MetalComposeGpuContext, NativeHandle, MetalTextureTarget>(
    presentationHost,
    RenderBackendPair(MapRenderBackend.Metal, ComposeRenderBackend.Metal),
    "maplibre-metal-host-renderer",
    SkiaTexturePresenter(MetalTextureWrapper),
    "Compose changed Metal devices; recreating the map renderer",
  ) {
  override fun deviceOf(context: MetalComposeGpuContext): NativeHandle = context.device

  override fun ringTarget(device: NativeHandle, extent: MapExtent, generation: Long) =
    IoSurfaceRingTarget(device, SessionRingDepth, extent, generation)

  override fun openFrame(
    context: MetalComposeGpuContext,
    frame: AcquiredFrameHandle,
  ): MetalTextureTarget = frame.withIoSurface { surface ->
    val address = surface.surface.address
    ringTextures.getOrOpen(surface.slot.toInt(), address) {
      val width = surface.width.toInt()
      val height = surface.height.toInt()
      MetalTextureTarget(
        texture =
          NativeHandle(MetalTexture.wrapIoSurface(context.device.address, address, width, height)),
        pixelFormat = MetalTexture.MTLPixelFormatBGRA8Unorm,
        origin = TextureOrigin.TopLeft,
        extent = MapExtent.fromPhysical(width, height, surface.scaleFactor),
        generation = 0,
      )
    }
  }

  override fun dispose(texture: MetalTextureTarget) {
    MetalTexture.dispose(texture.texture.address)
  }

  // The session owns its Metal objects.
  override fun closeProducers() {}

  override fun <R> onRendererThread(action: () -> R): R = ObjectiveC.runInAutoreleasePool(action)
}
