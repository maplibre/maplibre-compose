package org.maplibre.compose.map

import kotlinx.coroutines.Deferred
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MetalSurfaceTarget
import org.maplibre.compose.mlnffi.MetalTextureTarget
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.compose.mlnffi.VulkanImageTarget
import org.maplibre.compose.mlnffi.VulkanSurfaceTarget
import org.maplibre.nativeffi.MapHandle
import org.maplibre.nativeffi.MetalBorrowedTexture
import org.maplibre.nativeffi.MetalBorrowedTextureDescriptor
import org.maplibre.nativeffi.MetalContextDescriptor
import org.maplibre.nativeffi.MetalSurfaceDescriptor
import org.maplibre.nativeffi.NativePointer
import org.maplibre.nativeffi.NewRenderSession
import org.maplibre.nativeffi.RenderSessionAttachOptions
import org.maplibre.nativeffi.RenderSessionHandle
import org.maplibre.nativeffi.VulkanBorrowedTexture
import org.maplibre.nativeffi.VulkanBorrowedTextureDescriptor
import org.maplibre.nativeffi.VulkanSurfaceDescriptor

internal expect fun attachPlatformTarget(
  map: MapHandle,
  target: MlnFfiRenderTarget,
  extent: MapExtent,
  options: RenderSessionAttachOptions,
): NewRenderSession

internal expect fun replacePlatformTarget(
  session: RenderSessionHandle,
  target: MlnFfiRenderTarget,
  extent: MapExtent,
): Deferred<*>

internal fun VulkanImageTarget.toDescriptor(extent: MapExtent) =
  VulkanBorrowedTextureDescriptor(
    extent = extent.toFfiExtent(),
    physicalWidth = extent.physicalWidth.coerceAtLeast(1).toUInt(),
    physicalHeight = extent.physicalHeight.coerceAtLeast(1).toUInt(),
    context = context.toFfi(),
    textures =
      listOf(
        VulkanBorrowedTexture(
          image = image.address.toULong(),
          imageView = imageView.address.toULong(),
        ),
      ),
    format = format.toUInt(),
    initialLayout = initialLayout.toUInt(),
    finalLayout = finalLayout.toUInt(),
  )

internal fun VulkanSurfaceTarget.toDescriptor(extent: MapExtent) =
  VulkanSurfaceDescriptor(
    extent = extent.toFfiExtent(),
    context = context.toFfi(),
    surface = surface.address.toULong(),
  )

internal fun MetalTextureTarget.toDescriptor(extent: MapExtent) =
  MetalBorrowedTextureDescriptor(
    extent = extent.toFfiExtent(),
    physicalWidth = extent.physicalWidth.coerceAtLeast(1).toUInt(),
    physicalHeight = extent.physicalHeight.coerceAtLeast(1).toUInt(),
    textures = listOf(MetalBorrowedTexture(NativePointer.ofAddress(texture.address))),
  )

internal fun MetalSurfaceTarget.toDescriptor(extent: MapExtent) =
  MetalSurfaceDescriptor(
    extent = extent.toFfiExtent(),
    context = MetalContextDescriptor(device = NativePointer.ofAddress(device.address)),
    layer = NativePointer.ofAddress(layer.address),
  )

/** Loads platform graphics libraries before the FFI resolves their entry points. */
internal expect fun initializePlatformGraphics(backends: Set<MapRenderBackend>)
