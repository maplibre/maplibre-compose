@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package org.maplibre.compose.mlnffi

import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObject
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.objcPtr
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
 * The producer renders straight into a shared-storage `MTLTexture`, so the bridge has no consumer
 * side: presentation is a no-op, and readback is a CPU read of the texture.
 */
internal class IosMetalTestRenderDriver private constructor(private val device: MTLDeviceProtocol) :
  FfiTestRenderDriver {
  private var texture: MTLTextureProtocol? = null
  private val retiredTextures = mutableListOf<MTLTextureProtocol>()
  private var extent = MapExtent.Empty
  private var generation = 0L

  override val backends = RenderBackendPair(MapRenderBackend.METAL, ComposeRenderBackend.METAL)

  override fun <T> withRendererAccess(action: () -> T): T = action()

  override fun resize(extent: MapExtent) {
    ensureTexture(extent)
  }

  override fun acquireFrame(
    frameId: Long,
    extent: MapExtent,
    presentationTimeNanos: Long?,
  ): MlnFfiMapFrameAcquisition {
    val texture = ensureTexture(extent)
    return MlnFfiMapFrameAcquisition.Acquired(
      MlnFfiMapFrame(
        frameId = frameId,
        extent = extent,
        target =
          MetalTextureTarget(
            texture = NativeHandle(texture.rawAddress()),
            pixelFormat = MTLPixelFormatBGRA8Unorm.toLong(),
            origin = TextureOrigin.TOP_LEFT,
            extent = extent,
            generation = generation,
          ),
        presentationTimeNanos = presentationTimeNanos,
      )
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

  /** The producer renders directly into the test texture, so there is nothing to present. */
  override fun present(target: MlnFfiRenderTarget): Boolean = true

  override fun discardPresentedFrame() {
    texture?.let(retiredTextures::add)
    texture = null
    extent = MapExtent.Empty
  }

  override fun readPixel(x: Int, y: Int): RgbaPixel = autoreleasepool {
    val texture = checkNotNull(texture) { "No iOS test frame has been rendered" }
    memScoped {
      val bytes = allocArray<UByteVar>(4)
      // Metal texture rows start at the top left, as fixture coordinates do.
      texture.getBytes(
        bytes,
        BYTES_PER_ROW,
        MTLRegionMake2D(x.toULong(), y.toULong(), 1u, 1u),
        0uL,
      )
      // BGRA8Unorm stores blue in the first byte.
      RgbaPixel(
        red = bytes[2].toInt(),
        green = bytes[1].toInt(),
        blue = bytes[0].toInt(),
        alpha = bytes[3].toInt(),
      )
    }
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
    retiredTextures.clear()
    extent = MapExtent.Empty
  }

  companion object {
    private const val BYTES_PER_ROW = 4uL

    fun create(): IosMetalTestRenderDriver {
      val device =
        checkNotNull(MTLCreateSystemDefaultDevice()) { "The iOS simulator has no Metal device" }
      return IosMetalTestRenderDriver(device)
    }

    /** The `id<MTLTexture>` address, borrowed; the Kotlin reference keeps the object alive. */
    fun MTLTextureProtocol.rawAddress(): Long = (this as ObjCObject).objcPtr().toLong()
  }
}
