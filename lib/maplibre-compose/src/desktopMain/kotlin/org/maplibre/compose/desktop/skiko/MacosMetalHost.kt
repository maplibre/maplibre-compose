package org.maplibre.compose.desktop.skiko

import androidx.compose.ui.graphics.drawscope.DrawScope
import org.maplibre.compose.desktop.ComposeRenderBackend
import org.maplibre.compose.desktop.DesktopBackendPair
import org.maplibre.compose.desktop.DesktopHostCapabilities
import org.maplibre.compose.desktop.DesktopMapExtent
import org.maplibre.compose.desktop.DesktopMapFrame
import org.maplibre.compose.desktop.DesktopMapHost
import org.maplibre.compose.desktop.DesktopRenderTarget
import org.maplibre.compose.desktop.MapRenderBackend
import org.maplibre.compose.desktop.MetalTextureTarget
import org.maplibre.compose.desktop.NativeHandle
import org.maplibre.compose.desktop.TextureOrigin

/**
 * Bridges MapLibre's Metal rendering into Compose's Metal context on macOS.
 *
 * Both sides speak Metal, so there is nothing to export, import, or copy: the host allocates an
 * `id<MTLTexture>` on the same `id<MTLDevice>` Skiko renders with, MapLibre renders into it, and
 * Skia wraps that same texture to composite it. This is the simplest of the three bridges, and the
 * only one whose producer and consumer share an API.
 *
 * Ported from the `maplibre-native-ffi` Compose example, which is the reference for this path.
 */
internal class MacosMetalHost : DesktopMapHost {
  private val rendererThread = HostRendererThread("maplibre-macos-metal-renderer")
  private var texture = NativeHandle(0L)
  private var pixelFormat = 0L
  private var generation = 0L
  private var currentExtent = DesktopMapExtent.Empty

  override val backends: DesktopBackendPair =
    DesktopBackendPair(MapRenderBackend.METAL, ComposeRenderBackend.METAL)

  override val capabilities: DesktopHostCapabilities =
    DesktopHostCapabilities(
      backends = backends,
      // Ported as-is: the example performs no fence or event handshake between MapLibre's command
      // buffer and Skia's.
      // TODO(maplibre-compose): unverified on macOS — whether MapLibre's Metal renderUpdate has
      // finished on the GPU by the time draw() samples the texture, or whether that holds only
      // because both sides happen to submit to the same device.
      supportsExplicitSynchronization = false,
      supportsResizeWithoutRecreate = false,
    )

  override fun resize(extent: DesktopMapExtent) {
    // Skiko's device is read on the caller's thread rather than inside the renderer thread: reading
    // it hops to the AWT event thread and waits, and the renderer thread must never be the one
    // blocked on the EDT.
    val metalDevice = if (extent.isEmpty) null else SkikoReflection.requireMetalDevice()
    rendererThread.run { resizeOnRendererThread(extent, metalDevice) }
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
   * Runs [action] on the renderer thread, inside an autorelease pool.
   *
   * MapLibre's Metal backend returns autoreleased command buffers and encoders from `renderUpdate`;
   * the FFI documentation requires that call to happen inside a pool. The renderer thread is one we
   * created, so it has no pool of its own and every object would otherwise leak until the process
   * exits.
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
    return SkikoMetalPresenter.draw(scope, target)
  }

  override fun close() {
    try {
      // Preserved from the example: the texture, and the Skia surface wrapping it, are released on
      // the closing thread rather than on the renderer thread that is about to shut down.
      // TODO(maplibre-compose): unverified on macOS — whether closing that Skia surface off the AWT
      // event thread is safe, or whether it needs the onEdt hop the presenter uses to reach Skiko.
      disposeTexture()
    } finally {
      rendererThread.close()
    }
  }

  private fun resizeOnRendererThread(
    extent: DesktopMapExtent,
    metalDevice: SkikoMetalDevice? = null,
  ) {
    if (extent == currentExtent && !texture.isNull) {
      return
    }
    recreateTexture(extent, metalDevice)
    currentExtent = extent
    generation += 1
  }

  private fun target(extent: DesktopMapExtent, generation: Long): DesktopRenderTarget =
    MetalTextureTarget(
      texture =
        texture.takeIf { !it.isNull }
          ?: throw DesktopHostException("Skiko Metal texture allocation returned null"),
      pixelFormat = pixelFormat,
      // Metal textures are addressed from the top left, and the host allocates this one itself, so
      // the row order is never in question.
      origin = TextureOrigin.TOP_LEFT,
      extent = extent,
      generation = generation,
    )

  private fun recreateTexture(extent: DesktopMapExtent, metalDevice: SkikoMetalDevice? = null) {
    if (extent.isEmpty) {
      disposeTexture()
      return
    }
    val oldTexture = texture
    val textureAddress =
      MacosMetalTexture.create(
        metalDevice = (metalDevice ?: SkikoReflection.requireMetalDevice()).ptr,
        oldTexture = oldTexture.address,
        width = extent.physicalWidth,
        height = extent.physicalHeight,
      )
    // The allocation is skipped when the physical size did not change, in which case the old
    // texture comes back and must not be released. The generation still advances, because the
    // logical extent the session renders with did change.
    if (textureAddress != oldTexture.address) {
      releaseTexture(oldTexture)
    }
    texture = NativeHandle(textureAddress)
    pixelFormat = MacosMetalTexture.pixelFormat(textureAddress)
  }

  private fun disposeTexture() {
    releaseTexture(texture)
    texture = NativeHandle(0L)
    pixelFormat = 0
  }

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
 * The `MTLTexture` MapLibre renders into, allocated by hand through Objective-C.
 *
 * Every entry point opens an autorelease pool: Metal's factory methods return autoreleased objects,
 * and these are called from threads that have no pool of their own.
 */
private object MacosMetalTexture {
  private const val MTL_TEXTURE_TYPE_2D = 2L
  private const val MTL_PIXEL_FORMAT_BGRA8_UNORM = 80L
  private const val MTL_TEXTURE_USAGE_SHADER_READ = 1L
  private const val MTL_TEXTURE_USAGE_RENDER_TARGET = 4L
  private const val MTL_STORAGE_MODE_PRIVATE = 2L

  /**
   * Allocates a texture of [width] by [height] physical pixels, reusing [oldTexture] if it already
   * has that size.
   *
   * Reuse matters because a resize otherwise reallocates on every intermediate size a window drag
   * passes through. The returned address is owned by the caller unless it is [oldTexture].
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

      // Skiko's device object is its own Objective-C wrapper; `adapter` is what responds to
      // `newTextureWithDescriptor:`, so it is the `id<MTLDevice>` inside it.
      // TODO(maplibre-compose): unverified on macOS — that Skiko still names this property
      // `adapter`; it is not part of any published API and only the example's usage attests to it.
      val adapter = MacosObjectiveC.sendPointer(metalDevice, "adapter")
      val descriptor = MacosObjectiveC.allocInit("MTLTextureDescriptor")
      try {
        MacosObjectiveC.sendVoid(descriptor, "setTextureType:", MTL_TEXTURE_TYPE_2D)
        MacosObjectiveC.sendVoid(descriptor, "setPixelFormat:", MTL_PIXEL_FORMAT_BGRA8_UNORM)
        MacosObjectiveC.sendVoid(descriptor, "setWidth:", width.toLong())
        MacosObjectiveC.sendVoid(descriptor, "setHeight:", height.toLong())
        // Rendered into by MapLibre, sampled by Skia; private storage keeps it GPU-only, which is
        // the only mode that costs nothing on discrete hardware.
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
