package org.maplibre.compose.desktop.skiko

import androidx.compose.ui.graphics.drawscope.DrawScope
import org.maplibre.compose.desktop.ComposeRenderBackend
import org.maplibre.compose.desktop.DesktopBackendPair
import org.maplibre.compose.desktop.DesktopMapExtent
import org.maplibre.compose.desktop.DesktopMapFrame
import org.maplibre.compose.desktop.DesktopMapHost
import org.maplibre.compose.desktop.DesktopRenderTarget
import org.maplibre.compose.desktop.MapRenderBackend
import org.maplibre.compose.desktop.MetalTextureTarget
import org.maplibre.compose.desktop.NativeHandle
import org.maplibre.compose.desktop.TextureOrigin

/**
 * Bridges MapLibre's Metal rendering into Compose's Metal context on macOS: the host allocates an
 * `id<MTLTexture>` on the same `id<MTLDevice>` Skiko renders with, and Skia wraps that texture.
 *
 * There is no fence in either direction. MapLibre's Metal texture backend commits and
 * `waitUntilCompleted()`s inside `renderUpdate` — traced through maplibre-native-ffi 2c397595, from
 * `render_session_common.cpp:1388` to `renderer_impl.cpp:457` to `mtl/command_encoder.cpp:30` to
 * `metal_texture_backend.mm:139` — so the renderer thread blocks for the whole frame, every frame.
 * Correctness of the reverse direction rests on the frame loop issuing render and draw from a
 * single thread.
 */
internal class MacosMetalHost : DesktopMapHost {
  private val rendererThread = HostRendererThread("maplibre-macos-metal-renderer")
  private var texture = NativeHandle(0L)
  private var pixelFormat = 0L
  private var generation = 0L
  private var currentExtent = DesktopMapExtent.Empty

  /**
   * Textures this host has replaced but must still be able to present: the surface keeps presenting
   * the last target rendered into while MapLibre catches up with a new size (see
   * [DesktopRenderTarget.generation]), so a texture retired half a frame ago can still reach
   * [draw].
   */
  private val retiredTextures = ArrayDeque<NativeHandle>()

  override val backends: DesktopBackendPair =
    DesktopBackendPair(MapRenderBackend.METAL, ComposeRenderBackend.METAL)

  override fun resize(extent: DesktopMapExtent) {
    // Reading Skiko's device hops to the AWT event thread and waits, so it must not happen on the
    // renderer thread, which the event thread may itself be waiting on.
    val metalDevice = if (extent.isEmpty) null else SkikoReflection.requireMetalDevice()
    // Same rule for retiring the old texture: dropping its Skia wrapper also waits on the AWT event
    // thread, so the renderer hop hands it back instead of releasing it.
    val retired = rendererThread.run { resizeOnRendererThread(extent, metalDevice) }
    retired?.let(retiredTextures::addLast)
  }

  override fun acquireFrame(
    frameId: Long,
    extent: DesktopMapExtent,
    presentationTimeNanos: Long?,
  ): DesktopMapFrame {
    if (texture.isNull || extent != currentExtent) {
      resize(extent)
    }
    return HostFrame(
      frameId = frameId,
      extent = extent,
      target = target(extent, generation),
      presentationTimeNanos = presentationTimeNanos,
    )
  }

  /**
   * Runs [action] on the renderer thread, inside an autorelease pool. The FFI requires
   * `renderUpdate` to run inside a pool, and this thread has none of its own.
   */
  override fun <T> withProducerAccess(frame: DesktopMapFrame, action: () -> T): T =
    rendererThread.run {
      MacosObjectiveC.runInAutoreleasePool(action)
    }

  override fun <T> withRendererAccess(action: () -> T): T = rendererThread.run(action)

  override fun draw(scope: DrawScope, target: DesktopRenderTarget): Boolean {
    if (target !is MetalTextureTarget || target.texture.isNull) {
      return false
    }
    // First moment the host knows which retired texture the surface has stopped presenting.
    releaseRetiredTexturesExcept(target.texture)
    return SkikoMetalPresenter.draw(scope, target)
  }

  override fun close() {
    try {
      // Released on the closing thread, not the renderer thread; see [releaseTexture].
      takeTexture()?.let(retiredTextures::addLast)
      releaseRetiredTexturesExcept(NativeHandle(0L))
    } finally {
      rendererThread.close()
    }
  }

  /** Frees every retired texture except [keepAlive], which the surface is still presenting. */
  private fun releaseRetiredTexturesExcept(keepAlive: NativeHandle) {
    val iterator = retiredTextures.iterator()
    while (iterator.hasNext()) {
      val retired = iterator.next()
      if (retired == keepAlive) continue
      iterator.remove()
      releaseTexture(retired)
    }
  }

  /** Reallocates the texture, returning the one it replaced for the caller to release. */
  private fun resizeOnRendererThread(
    extent: DesktopMapExtent,
    metalDevice: SkikoMetalDevice? = null,
  ): NativeHandle? {
    if (extent == currentExtent && !texture.isNull) {
      return null
    }
    val retired = recreateTexture(extent, metalDevice)
    currentExtent = extent
    generation += 1
    return retired
  }

  private fun target(extent: DesktopMapExtent, generation: Long): DesktopRenderTarget =
    MetalTextureTarget(
      texture =
        texture.takeIf { !it.isNull }
          ?: throw DesktopHostException("Skiko Metal texture allocation returned null"),
      pixelFormat = pixelFormat,
      origin = TextureOrigin.TOP_LEFT,
      extent = extent,
      generation = generation,
    )

  /** Allocates the texture for [extent], returning the one it replaced, if any. */
  private fun recreateTexture(
    extent: DesktopMapExtent,
    metalDevice: SkikoMetalDevice? = null,
  ): NativeHandle? {
    if (extent.isEmpty) return takeTexture()

    val oldTexture = texture
    val textureAddress =
      MacosMetalTexture.create(
        metalDevice = (metalDevice ?: SkikoReflection.requireMetalDevice()).ptr,
        oldTexture = oldTexture.address,
        width = extent.physicalWidth,
        height = extent.physicalHeight,
      )
    texture = NativeHandle(textureAddress)
    pixelFormat = MacosMetalTexture.pixelFormat(textureAddress)
    // create() reuses the old texture when the physical size is unchanged; don't release it then.
    return oldTexture.takeIf { !it.isNull && textureAddress != it.address }
  }

  /** Clears the current texture, returning it for the caller to release. */
  private fun takeTexture(): NativeHandle? {
    val retired = texture.takeIf { !it.isNull }
    texture = NativeHandle(0L)
    pixelFormat = 0
    return retired
  }

  /**
   * Releases a retired texture, and the Skia surface wrapping it, on the calling thread. Never call
   * this from the renderer thread: both halves wait on the AWT event thread, which deadlocks.
   */
  private fun releaseTexture(texture: NativeHandle) {
    if (texture.isNull) {
      return
    }
    // Skia holds a surface wrapping this texture; it must be dropped before the texture is.
    SkikoMetalPresenter.forget(texture)
    MacosMetalTexture.dispose(texture.address)
  }

  private class HostFrame(
    override val frameId: Long,
    override val extent: DesktopMapExtent,
    override val target: DesktopRenderTarget,
    override val presentationTimeNanos: Long?,
  ) : DesktopMapFrame
}

/**
 * The `MTLTexture` MapLibre renders into, allocated by hand through Objective-C. Every entry point
 * opens an autorelease pool, since these run on threads that have none of their own.
 */
internal object MacosMetalTexture {
  /**
   * Skiko's Objective-C wrapper around the Metal device, and the property on it holding the
   * `id<MTLDevice>`. Neither is published API (private to Skiko's `MetalRedrawer.mm`), so both are
   * pinned by `MacosMetalDeviceContractTest`.
   */
  const val SKIKO_METAL_DEVICE_CLASS: String = "MetalDevice"

  const val SKIKO_METAL_DEVICE_ADAPTER: String = "adapter"

  private const val MTL_TEXTURE_TYPE_2D = 2L
  private const val MTL_PIXEL_FORMAT_BGRA8_UNORM = 80L
  private const val MTL_TEXTURE_USAGE_SHADER_READ = 1L
  private const val MTL_TEXTURE_USAGE_RENDER_TARGET = 4L
  private const val MTL_STORAGE_MODE_PRIVATE = 2L

  /**
   * Allocates a texture of [width] by [height] physical pixels, reusing [oldTexture] if it already
   * has that size. The returned address is owned by the caller unless it is [oldTexture].
   */
  fun create(metalDevice: Long, oldTexture: Long, width: Int, height: Int): Long =
    MacosObjectiveC.autoreleasePool().use {
      if (oldTexture != 0L) {
        val oldWidth = MacosObjectiveC.sendLong(oldTexture, "width")
        val oldHeight = MacosObjectiveC.sendLong(oldTexture, "height")
        if (oldWidth == width.toLong() && oldHeight == height.toLong()) {
          return oldTexture
        }
      }

      // Skiko's device object is its own wrapper; `adapter` holds the real `id<MTLDevice>`.
      val adapter = MacosObjectiveC.sendPointer(metalDevice, SKIKO_METAL_DEVICE_ADAPTER)
      val descriptor = MacosObjectiveC.allocInit("MTLTextureDescriptor")
      try {
        MacosObjectiveC.sendVoid(descriptor, "setTextureType:", MTL_TEXTURE_TYPE_2D)
        MacosObjectiveC.sendVoid(descriptor, "setPixelFormat:", MTL_PIXEL_FORMAT_BGRA8_UNORM)
        MacosObjectiveC.sendVoid(descriptor, "setWidth:", width.toLong())
        MacosObjectiveC.sendVoid(descriptor, "setHeight:", height.toLong())
        // Rendered into by MapLibre, sampled by Skia; private storage keeps it GPU-only.
        MacosObjectiveC.sendVoid(
          descriptor,
          "setUsage:",
          MTL_TEXTURE_USAGE_SHADER_READ or MTL_TEXTURE_USAGE_RENDER_TARGET,
        )
        MacosObjectiveC.sendVoid(descriptor, "setStorageMode:", MTL_STORAGE_MODE_PRIVATE)
        val texture = MacosObjectiveC.sendPointer(adapter, "newTextureWithDescriptor:", descriptor)
        if (texture == 0L) {
          throw DesktopHostException("Skiko Metal texture allocation returned null")
        }
        texture
      } finally {
        MacosObjectiveC.release(descriptor)
      }
    }

  fun dispose(texture: Long) {
    MacosObjectiveC.autoreleasePool().use { MacosObjectiveC.release(texture) }
  }

  fun pixelFormat(texture: Long): Long =
    MacosObjectiveC.autoreleasePool().use {
      if (texture == 0L) 0L else MacosObjectiveC.sendLong(texture, "pixelFormat")
    }
}
