package org.maplibre.compose.map

import org.maplibre.compose.mlnffi.EglContextHandles
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MetalSurfaceTarget
import org.maplibre.compose.mlnffi.MetalTextureTarget
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.compose.mlnffi.OpenGlContextHandles
import org.maplibre.compose.mlnffi.OpenGlSurfaceTarget
import org.maplibre.compose.mlnffi.OpenGlTextureTarget
import org.maplibre.compose.mlnffi.VulkanContextHandles
import org.maplibre.compose.mlnffi.VulkanImageTarget
import org.maplibre.compose.mlnffi.WglContextHandles
import org.maplibre.nativeffi.Completion
import org.maplibre.nativeffi.EglContextDescriptor
import org.maplibre.nativeffi.GpuDeviceHandle
import org.maplibre.nativeffi.LogicalExtent
import org.maplibre.nativeffi.LogicalSize
import org.maplibre.nativeffi.MapHandle
import org.maplibre.nativeffi.MetalBorrowedTexture
import org.maplibre.nativeffi.MetalBorrowedTexturesTarget
import org.maplibre.nativeffi.MetalLayerTarget
import org.maplibre.nativeffi.NativePointer
import org.maplibre.nativeffi.OpenGlBorrowedTexture
import org.maplibre.nativeffi.OpenGlBorrowedTexturesTarget
import org.maplibre.nativeffi.OpenGlContextDescriptor
import org.maplibre.nativeffi.OpenGlSurfaceTarget as FfiOpenGlSurfaceTarget
import org.maplibre.nativeffi.RenderSessionAttachOptions
import org.maplibre.nativeffi.RenderSessionHandle
import org.maplibre.nativeffi.RenderTarget
import org.maplibre.nativeffi.VulkanBorrowedTexture
import org.maplibre.nativeffi.VulkanBorrowedTexturesTarget
import org.maplibre.nativeffi.VulkanContextDescriptor
import org.maplibre.nativeffi.WglContextDescriptor

/**
 * Attaches a session to a target that only a platform source set defines, such as a window, on
 * [device].
 */
internal expect fun attachPlatformTarget(
  map: MapHandle,
  target: MlnFfiRenderTarget,
  extent: MapExtent,
  device: GpuDeviceHandle?,
  options: RenderSessionAttachOptions,
): RenderSessionHandle

/** Gives [session] the window of a target that only a platform source set defines. */
internal expect fun replacePlatformTarget(
  session: RenderSessionHandle,
  target: MlnFfiRenderTarget,
  extent: MapExtent,
): Completion<*>

/** Attaches a session that renders into [target] on [device]. */
internal fun MapHandle.attachTarget(
  target: MlnFfiRenderTarget,
  extent: MapExtent,
  device: GpuDeviceHandle?,
  options: RenderSessionAttachOptions,
): RenderSessionHandle =
  target.toRenderTarget(extent)?.let { attach(it, device, options) }
    ?: attachPlatformTarget(this, target, extent, device, options)

/** Gives [session] [target]; a session renders only into targets of its device. */
internal fun RenderSessionHandle.replaceTarget(
  target: MlnFfiRenderTarget,
  extent: MapExtent,
): Completion<*> =
  target.toRenderTarget(extent)?.let { setTarget(it) }
    ?: replacePlatformTarget(this, target, extent)

/**
 * Wraps the host's Vulkan or Metal device that owns [target], so that a session renders into the
 * host's GPU objects. Returns null for a target whose session creates its own device.
 */
internal expect fun wrapPlatformDevice(target: MlnFfiRenderTarget): GpuDeviceHandle?

/**
 * The device a session on [target] renders on, which the caller releases once the session's release
 * completes, or null for a device that the session creates and releases itself.
 */
internal fun wrapTargetDevice(target: MlnFfiRenderTarget): GpuDeviceHandle? =
  when (target) {
    is OpenGlTextureTarget -> GpuDeviceHandle.wrapOpenGl(target.context.toFfi())
    is OpenGlSurfaceTarget -> GpuDeviceHandle.wrapOpenGl(target.context.toFfi())
    else -> wrapPlatformDevice(target)
  }

/**
 * Identifies the host GPU objects that a session on this target renders with. A session renders on
 * the device it attached with, so it can move only to targets with an equal key.
 */
internal val MlnFfiRenderTarget.deviceKey: Any?
  get() =
    when (this) {
      is VulkanImageTarget -> context
      is MetalTextureTarget -> device
      is MetalSurfaceTarget -> device
      is OpenGlTextureTarget -> context
      is OpenGlSurfaceTarget -> context
      else -> null
    }

/**
 * The FFI render target that renders into [this] at [extent], or null for a target that only a
 * platform source set defines.
 */
private fun MlnFfiRenderTarget.toRenderTarget(extent: MapExtent): RenderTarget? =
  when (this) {
    is VulkanImageTarget ->
      RenderTarget.VulkanBorrowedTextures(
        VulkanBorrowedTexturesTarget(
          logicalSize = extent.toLogicalSize(),
          physicalWidth = extent.physicalWidth.coerceAtLeast(1).toUInt(),
          physicalHeight = extent.physicalHeight.coerceAtLeast(1).toUInt(),
          textures =
            listOf(
              VulkanBorrowedTexture(
                image = image.address.toULong(),
                imageView = imageView.address.toULong(),
              ),
            ),
          format = format.toUInt(),
          finalLayout = finalLayout.toUInt(),
        ),
      )
    is MetalTextureTarget ->
      RenderTarget.MetalBorrowedTextures(
        MetalBorrowedTexturesTarget(
          logicalSize = extent.toLogicalSize(),
          physicalWidth = extent.physicalWidth.coerceAtLeast(1).toUInt(),
          physicalHeight = extent.physicalHeight.coerceAtLeast(1).toUInt(),
          textures = listOf(MetalBorrowedTexture(NativePointer.ofAddress(texture.address))),
        ),
      )
    is MetalSurfaceTarget ->
      RenderTarget.MetalLayer(
        MetalLayerTarget(
          logicalSize = extent.toLogicalSize(),
          layer = NativePointer.ofAddress(layer.address),
        ),
      )
    is OpenGlTextureTarget ->
      RenderTarget.OpenGlBorrowedTextures(
        OpenGlBorrowedTexturesTarget(
          logicalSize = extent.toLogicalSize(),
          physicalWidth = extent.physicalWidth.coerceAtLeast(1).toUInt(),
          physicalHeight = extent.physicalHeight.coerceAtLeast(1).toUInt(),
          textures = listOf(OpenGlBorrowedTexture(textureName.toUInt())),
          target = textureTarget.toUInt(),
        ),
      )
    is OpenGlSurfaceTarget ->
      RenderTarget.OpenGlSurface(
        FfiOpenGlSurfaceTarget(
          logicalSize = extent.toLogicalSize(),
          surface = NativePointer.ofAddress(surface.address),
        ),
      )
    else -> null
  }

/**
 * The target's size in logical pixels, which the map's scale factor turns into pixels. MapLibre
 * rejects an empty size.
 */
internal fun MapExtent.toLogicalSize() =
  LogicalSize(width = width.coerceAtLeast(1).toUInt(), height = height.coerceAtLeast(1).toUInt())

/** The target's size in logical pixels, with its scale factor. MapLibre rejects an empty size. */
internal fun MapExtent.toLogicalExtent() =
  LogicalExtent(
    width = width.coerceAtLeast(1).toUInt(),
    height = height.coerceAtLeast(1).toUInt(),
    scaleFactor = scaleFactor,
  )

internal fun VulkanContextHandles.toFfi() =
  VulkanContextDescriptor(
    instance = NativePointer.ofAddress(instance.address),
    physicalDevice = NativePointer.ofAddress(physicalDevice.address),
    device = NativePointer.ofAddress(device.address),
    graphicsQueue = NativePointer.ofAddress(graphicsQueue.address),
    graphicsQueueFamilyIndex = graphicsQueueFamilyIndex.toUInt(),
    getInstanceProcAddr = NativePointer.ofAddress(getInstanceProcAddr.address),
    getDeviceProcAddr = NativePointer.ofAddress(getDeviceProcAddr.address),
  )

internal fun OpenGlContextHandles.toFfi(): OpenGlContextDescriptor =
  when (this) {
    is EglContextHandles ->
      OpenGlContextDescriptor.Egl(
        EglContextDescriptor(
          display = NativePointer.ofAddress(display.address),
          config = NativePointer.ofAddress(config.address),
          shareContext = NativePointer.ofAddress(shareContext.address),
          getProcAddress = NativePointer.ofAddress(getProcAddress.address),
        ),
      )
    is WglContextHandles ->
      OpenGlContextDescriptor.Wgl(
        WglContextDescriptor(
          deviceContext = NativePointer.ofAddress(deviceContext.address),
          shareContext = NativePointer.ofAddress(shareContext.address),
          getProcAddress = NativePointer.ofAddress(getProcAddress.address),
        ),
      )
  }

/** Loads platform graphics libraries before the FFI resolves their entry points. */
internal expect fun initializePlatformGraphics(backends: Set<MapRenderBackend>)
