@file:OptIn(org.maplibre.compose.util.ExperimentalMaplibreComposeApi::class)

package org.maplibre.compose.desktop.bridge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.jetbrains.skia.SurfaceColorFormat
import org.maplibre.compose.desktop.Direct3D12ComposeGpuContext
import org.maplibre.compose.desktop.Direct3D12PresentationHost
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.DxgiSharedTextureRingTarget
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiHostException
import org.maplibre.compose.mlnffi.NativeHandle
import org.maplibre.compose.mlnffi.RenderBackendPair
import org.maplibre.compose.mlnffi.TextureOrigin
import org.maplibre.nativeffi.AcquiredFrameHandle
import org.maplibre.nativeffi.VulkanDeviceHandle
import org.maplibre.nativeffi.VulkanDeviceOptions
import org.maplibre.nativeffi.create
import org.maplibre.nativeffi.withDxgiSharedTexture

/**
 * Bridges MapLibre's Vulkan rendering into Compose's Direct3D 12 context on Windows. The session
 * renders with a Vulkan device on Compose's adapter into a ring of D3D11 textures it allocates, and
 * the host opens each texture's shared handle on Compose's `ID3D12Device` for Skia to draw.
 */
internal class DxgiSharedTextureRingMapHost(presentationHost: Direct3D12PresentationHost) :
  SessionRingMapHost<Direct3D12ComposeGpuContext, NativeHandle, Direct3DTextureTarget>(
    presentationHost,
    RenderBackendPair(MapRenderBackend.Vulkan, ComposeRenderBackend.Direct3D12),
    "maplibre-windows-map-renderer",
    SkiaTexturePresenter(Direct3DTextureWrapper),
    Direct3D12DeviceChangeMessage,
  ) {
  /**
   * The session's Vulkan device, on Compose's adapter. Created and closed on the renderer thread.
   */
  @Volatile private var vulkan: VulkanDeviceHandle? = null

  override fun deviceOf(context: Direct3D12ComposeGpuContext): NativeHandle = context.device

  override fun ringTarget(device: NativeHandle, extent: MapExtent, generation: Long) =
    DxgiSharedTextureRingTarget(vulkanDevice(device), SessionRingDepth, extent, generation)

  private fun vulkanDevice(device: NativeHandle): VulkanDeviceHandle {
    vulkan?.let {
      return it
    }
    val luid = WindowsDirect3DInterop.adapterLuidOf(device)
    return rendererThread
      .run {
        // The textures belong to the Vulkan device's GPU, so pick Compose's adapter by its LUID.
        VulkanDeviceHandle.create(VulkanDeviceOptions(deviceLuid = luid.toLuidBytes()))
      }
      .also { vulkan = it }
  }

  override fun openFrame(
    context: Direct3D12ComposeGpuContext,
    frame: AcquiredFrameHandle,
  ): Direct3DTextureTarget = frame.withDxgiSharedTexture { texture ->
    val handle = texture.handle.address
    ringTextures.getOrOpen(texture.slot.toInt(), handle) {
      val format = texture.format.toInt()
      if (format != DXGI_FORMAT_B8G8R8A8_UNORM) {
        throw MlnFfiHostException("The map's shared texture has unexpected DXGI_FORMAT $format")
      }
      Direct3DTextureTarget(
        texture = WindowsDirect3DInterop.openSharedHandle(context.device, handle),
        format = format,
        colorFormat = SurfaceColorFormat.BGRA_8888,
        origin = TextureOrigin.TopLeft,
        extent =
          MapExtent.fromPhysical(
            texture.width.toInt(),
            texture.height.toInt(),
            texture.scaleFactor,
          ),
        generation = 0,
      )
    }
  }

  override fun dispose(texture: Direct3DTextureTarget) {
    WindowsDirect3DInterop.release(texture.texture)
  }

  override fun closeProducers() {
    val closing = vulkan
    vulkan = null
    closing?.release()
  }
}

/** The 8 bytes of a Windows `LUID`, low part first, as Vulkan's `deviceLUID` holds them. */
private fun Long.toLuidBytes(): ByteArray =
  ByteBuffer.allocate(Long.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(this).array()
