package org.maplibre.compose.map

import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MetalSurfaceTarget
import org.maplibre.compose.mlnffi.MetalTextureTarget
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.compose.mlnffi.VulkanImageTarget
import org.maplibre.compose.mlnffi.VulkanSurfaceTarget
import org.maplibre.nativeffi.Completion
import org.maplibre.nativeffi.LogicalSize
import org.maplibre.nativeffi.MapHandle
import org.maplibre.nativeffi.MetalBorrowedTexture
import org.maplibre.nativeffi.MetalBorrowedTextureDescriptor
import org.maplibre.nativeffi.MetalBorrowedTextureReplacement
import org.maplibre.nativeffi.MetalContextDescriptor
import org.maplibre.nativeffi.MetalSurfaceDescriptor
import org.maplibre.nativeffi.MetalSurfaceReplacement
import org.maplibre.nativeffi.NativePointer
import org.maplibre.nativeffi.RenderSessionAttachOptions
import org.maplibre.nativeffi.RenderSessionHandle
import org.maplibre.nativeffi.VulkanBorrowedTexture
import org.maplibre.nativeffi.VulkanBorrowedTextureDescriptor
import org.maplibre.nativeffi.VulkanBorrowedTextureReplacement
import org.maplibre.nativeffi.VulkanSurfaceDescriptor

internal expect fun attachPlatformTarget(
  map: MapHandle,
  target: MlnFfiRenderTarget,
  extent: MapExtent,
  options: RenderSessionAttachOptions,
): RenderSessionHandle

internal expect fun replacePlatformTarget(
  session: RenderSessionHandle,
  target: MlnFfiRenderTarget,
  extent: MapExtent,
): Completion<*>

/**
 * The target's size in logical pixels, which the map's scale factor turns into pixels. MapLibre
 * rejects an empty size.
 */
internal fun MapExtent.toLogicalSize() =
  LogicalSize(width = width.coerceAtLeast(1).toUInt(), height = height.coerceAtLeast(1).toUInt())

internal fun VulkanImageTarget.toDescriptor(extent: MapExtent) =
  VulkanBorrowedTextureDescriptor(
    logicalSize = extent.toLogicalSize(),
    physicalWidth = extent.physicalWidth.coerceAtLeast(1).toUInt(),
    physicalHeight = extent.physicalHeight.coerceAtLeast(1).toUInt(),
    context = context.toFfi(),
    textures = listOf(toBorrowedTexture()),
    format = format.toUInt(),
    finalLayout = finalLayout.toUInt(),
  )

internal fun VulkanImageTarget.toReplacement(extent: MapExtent) =
  VulkanBorrowedTextureReplacement(
    logicalSize = extent.toLogicalSize(),
    physicalWidth = extent.physicalWidth.coerceAtLeast(1).toUInt(),
    physicalHeight = extent.physicalHeight.coerceAtLeast(1).toUInt(),
    textures = listOf(toBorrowedTexture()),
  )

private fun VulkanImageTarget.toBorrowedTexture() =
  VulkanBorrowedTexture(image = image.address.toULong(), imageView = imageView.address.toULong())

internal fun VulkanSurfaceTarget.toDescriptor(extent: MapExtent) =
  VulkanSurfaceDescriptor(
    logicalSize = extent.toLogicalSize(),
    context = context.toFfi(),
    surface = surface.address.toULong(),
  )

internal fun MetalTextureTarget.toDescriptor(extent: MapExtent) =
  MetalBorrowedTextureDescriptor(
    logicalSize = extent.toLogicalSize(),
    physicalWidth = extent.physicalWidth.coerceAtLeast(1).toUInt(),
    physicalHeight = extent.physicalHeight.coerceAtLeast(1).toUInt(),
    textures = listOf(MetalBorrowedTexture(NativePointer.ofAddress(texture.address))),
    // The session renders on a queue of its own, and its frames are complete when acquired.
    commandQueue = NativePointer.NULL_POINTER,
  )

internal fun MetalTextureTarget.toReplacement(extent: MapExtent) =
  MetalBorrowedTextureReplacement(
    logicalSize = extent.toLogicalSize(),
    physicalWidth = extent.physicalWidth.coerceAtLeast(1).toUInt(),
    physicalHeight = extent.physicalHeight.coerceAtLeast(1).toUInt(),
    textures = listOf(MetalBorrowedTexture(NativePointer.ofAddress(texture.address))),
  )

internal fun MetalSurfaceTarget.toDescriptor(extent: MapExtent) =
  MetalSurfaceDescriptor(
    logicalSize = extent.toLogicalSize(),
    context = MetalContextDescriptor(device = NativePointer.ofAddress(device.address)),
    layer = NativePointer.ofAddress(layer.address),
  )

internal fun MetalSurfaceTarget.toReplacement(extent: MapExtent) =
  MetalSurfaceReplacement(
    logicalSize = extent.toLogicalSize(),
    layer = NativePointer.ofAddress(layer.address),
  )

/** Loads platform graphics libraries before the FFI resolves their entry points. */
internal expect fun initializePlatformGraphics(backends: Set<MapRenderBackend>)
