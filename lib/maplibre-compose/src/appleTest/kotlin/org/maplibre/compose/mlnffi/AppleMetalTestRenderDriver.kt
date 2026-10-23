@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package org.maplibre.compose.mlnffi

import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObject
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toLong
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.testing.RgbaPixel
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.Metal.MTLDeviceProtocol
import platform.Metal.MTLPixelFormatBGRA8Unorm
import platform.Metal.MTLRegionMake2D
import platform.Metal.MTLStorageModeShared
import platform.Metal.MTLTextureDescriptor
import platform.Metal.MTLTextureProtocol
import platform.Metal.MTLTextureUsageRenderTarget
import platform.Metal.MTLTextureUsageShaderRead

/**
 * Deterministic offscreen Metal host for the shared real-map test corpus.
 *
 * The producer renders straight into a shared-storage `MTLTexture`. Presentation copies the texture
 * to memory while the frame is still acquired, because once the frame is released the next frame
 * can render into the same texture. Readback reads that copy.
 */
internal class AppleMetalTestRenderDriver
private constructor(private val device: MTLDeviceProtocol) : FfiTestRenderDriver {
  private var texture: MTLTextureProtocol? = null
  private val retiredTextures = mutableListOf<MTLTextureProtocol>()
  private var extent = MapExtent.Empty
  private var generation = 0L
  private var presented: ByteArray? = null
  private var presentedWidth = 0

  override val backends = RenderBackendPair(MapRenderBackend.Metal, ComposeRenderBackend.Metal)

  override fun <T> withRendererAccess(action: () -> T): T = action()

  override fun resize(extent: MapExtent) {
    ensureTexture(extent)
  }

  override fun acquireFrame(extent: MapExtent): MlnFfiMapFrameAcquisition {
    val texture = ensureTexture(extent)
    return MlnFfiMapFrameAcquisition.Acquired(
      MlnFfiMapFrame(
        target =
          MetalTextureTarget(
            texture = NativeHandle(texture.rawAddress()),
            pixelFormat = MTLPixelFormatBGRA8Unorm.toLong(),
            origin = TextureOrigin.TopLeft,
            extent = extent,
            generation = generation,
          ),
      ),
    )
  }

  /** The FFI runtime renders inside an autorelease pool; the test thread has none of its own. */
  override fun <T> withProducerAccess(frame: MlnFfiMapFrame, action: () -> T): T = autoreleasepool {
    action()
  }

  override fun draw(
    scope: DrawScope,
    target: MlnFfiRenderTarget,
    destination: MlnFfiMapDestination,
  ): Boolean = false

  /** Copies the rendered texture to memory, while the frame that holds it is still acquired. */
  override fun present(target: MlnFfiRenderTarget): Boolean = autoreleasepool {
    val texture = texture ?: return false
    val width = texture.width.toInt()
    val height = texture.height.toInt()
    val bytesPerRow = width * BytesPerPixel
    presented = memScoped {
      val bytes = allocArray<UByteVar>(bytesPerRow * height)
      // Metal texture rows start at the top left, as fixture coordinates do.
      texture.getBytes(
        bytes,
        bytesPerRow.toULong(),
        MTLRegionMake2D(0u, 0u, width.toULong(), height.toULong()),
        0uL,
      )
      bytes.readBytes(bytesPerRow * height)
    }
    presentedWidth = width
    true
  }

  override fun discardPresentedFrame() {
    texture?.let(retiredTextures::add)
    texture = null
    presented = null
    extent = MapExtent.Empty
  }

  override fun readPixel(x: Int, y: Int): RgbaPixel {
    val bytes = checkNotNull(presented) { "No iOS test frame has been presented" }
    val offset = (y * presentedWidth + x) * BytesPerPixel
    // BGRA8Unorm stores blue in the first byte.
    return RgbaPixel(
      red = bytes[offset + 2].toInt() and 0xff,
      green = bytes[offset + 1].toInt() and 0xff,
      blue = bytes[offset].toInt() and 0xff,
      alpha = bytes[offset + 3].toInt() and 0xff,
    )
  }

  private fun ensureTexture(next: MapExtent): MTLTextureProtocol = autoreleasepool {
    texture
      ?.takeIf { extent == next }
      ?.let {
        return it
      }
    val descriptor =
      MTLTextureDescriptor.texture2DDescriptorWithPixelFormat(
        MTLPixelFormatBGRA8Unorm,
        next.physicalWidth.coerceAtLeast(1).toULong(),
        next.physicalHeight.coerceAtLeast(1).toULong(),
        false,
      )
    descriptor.storageMode = MTLStorageModeShared
    descriptor.usage = MTLTextureUsageShaderRead or MTLTextureUsageRenderTarget
    val created =
      checkNotNull(device.newTextureWithDescriptor(descriptor)) {
        "The iOS test Metal device could not allocate a ${next.physicalWidth}x${next.physicalHeight} texture"
      }
    // A scale change makes MlnFfiMapSession close the old renderer after it receives the new
    // frame. That renderer still names the previous texture while closing, so keep old generations
    // alive until the fixture has closed every native render session.
    texture?.let(retiredTextures::add)
    texture = created
    extent = next
    generation++
    created
  }

  override fun close() {
    texture = null
    presented = null
    retiredTextures.clear()
    extent = MapExtent.Empty
  }

  companion object {
    private const val BytesPerPixel = 4

    fun create(): AppleMetalTestRenderDriver {
      val device =
        checkNotNull(MTLCreateSystemDefaultDevice()) { "The iOS simulator has no Metal device" }
      return AppleMetalTestRenderDriver(device)
    }

    /** The `id<MTLTexture>` address, borrowed; the Kotlin reference keeps the object alive. */
    fun MTLTextureProtocol.rawAddress(): Long = (this as ObjCObject).objcPtr().toLong()
  }
}
