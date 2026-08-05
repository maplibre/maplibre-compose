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
 * Bridges MapLibre's Metal rendering into Compose's Metal context on macOS.
 *
 * Both sides speak Metal, so there is nothing to export, import, or copy: the host allocates an
 * `id<MTLTexture>` on the same `id<MTLDevice>` Skiko renders with, MapLibre renders into it, and
 * Skia wraps that same texture to composite it. This is the simplest of the three bridges, and the
 * only one whose producer and consumer share an API.
 *
 * Ported from the `maplibre-native-ffi` Compose example, which is the reference for this path.
 *
 * It inserts no fence or event handshake between MapLibre's command buffer and Skia's, and needs
 * none: MapLibre's Metal texture backend commits its command buffer and then waits on it from
 * inside `renderUpdate`, so the texture is finished on the GPU before that call returns and `draw`
 * can sample it. Traced through maplibre-native-ffi 2c397595 — `render_session_common.cpp:1388`
 * renders the update, `renderer_impl.cpp:457` presents the default renderable,
 * `mtl/command_encoder.cpp:30` forwards that to the renderable's `swap()`, and
 * `metal_texture_backend.mm:139` commits and `waitUntilCompleted()`s. The cost is that the renderer
 * thread blocks for the whole frame, every frame.
 *
 * That ordering is the producer's doing rather than anything this bridge signals, and the reverse
 * direction is unfenced too: MapLibre overwriting the texture while Skia's previous frame may still
 * be sampling it rests on the frame loop issuing render and draw from one thread, not on a
 * guarantee from either API.
 */
internal class MacosMetalHost : DesktopMapHost {
  private val rendererThread = HostRendererThread("maplibre-macos-metal-renderer")
  private var texture = NativeHandle(0L)
  private var pixelFormat = 0L
  private var generation = 0L
  private var currentExtent = DesktopMapExtent.Empty

  /**
   * Textures this host has replaced but must still be able to present.
   *
   * See [DesktopRenderTarget.generation]: the surface keeps presenting the last target that was
   * rendered into while MapLibre catches up with a new size, so a texture retired half a frame ago
   * can still be handed back to [draw]. Releasing it on the generation bump, which is what this
   * host used to do, hands Skia a released `MTLTexture` and traps inside `CFRetain` — reproducible
   * by dragging a window edge, and found by the compose-glfw fixture, whose host reports every
   * intermediate size rather than only the ones AWT does not coalesce away.
   *
   * Bounded by construction: a texture leaves this list as soon as [draw] is asked for a different
   * one, so at most one resize's worth is held.
   */
  private val retiredTextures = ArrayDeque<NativeHandle>()

  override val backends: DesktopBackendPair =
    DesktopBackendPair(MapRenderBackend.METAL, ComposeRenderBackend.METAL)

  override fun resize(extent: DesktopMapExtent) {
    // Skiko's device is read on the caller's thread rather than inside the renderer thread: reading
    // it hops to the AWT event thread and waits, and the renderer thread must never be the one
    // blocked on the EDT.
    val metalDevice = if (extent.isEmpty) null else SkikoReflection.requireMetalDevice()
    // Retiring the old texture obeys the same rule, which is why the renderer hop hands it back
    // rather than releasing it: dropping its Skia wrapper also waits on the AWT event thread, and
    // the event thread is usually the thread waiting on this hop — it is the one that draws, and
    // drawing is what calls acquireFrame. Releasing inside the hop deadlocks both threads on the
    // first window resize, which is how this was found.
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
    // Freed here rather than at the resize that retired them, because this is the first moment the
    // host knows which of them the surface has stopped presenting.
    releaseRetiredTexturesExcept(target.texture)
    return SkikoMetalPresenter.draw(scope, target)
  }

  override fun close() {
    try {
      // The texture, and the Skia surface wrapping it, are released on the closing thread rather
      // than on the renderer thread that is about to shut down. The presenter hops to the AWT
      // event thread itself, so the Skia objects are always freed on the thread that owns them.
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
      // Metal textures are addressed from the top left, and the host allocates this one itself, so
      // the row order is never in question.
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
    // The allocation is skipped when the physical size did not change, in which case the old
    // texture comes back and must not be released. The generation still advances, because the
    // logical extent the session renders with did change.
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
   * Releases a retired texture, and the Skia surface wrapping it, on the calling thread.
   *
   * Never call this from the renderer thread. Both halves reach the AWT event thread and wait, so
   * doing it there deadlocks against an event thread that is waiting on the renderer.
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
 * The `MTLTexture` MapLibre renders into, allocated by hand through Objective-C.
 *
 * Every entry point opens an autorelease pool: Metal's factory methods return autoreleased objects,
 * and these are called from threads that have no pool of their own.
 */
internal object MacosMetalTexture {
  /**
   * Skiko's own Objective-C wrapper around the Metal device, under the name it registers with the
   * runtime, and the property on it holding the `id<MTLDevice>`.
   *
   * Neither is published API — they are private to Skiko's `MetalRedrawer.mm` — so they are named
   * here once and pinned by `MacosMetalDeviceContractTest`.
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
      // `newTextureWithDescriptor:`, so it is the `id<MTLDevice>` inside it. Confirmed against the
      // Skiko this project resolves by dumping the Objective-C metadata of
      // libskiko-macos-arm64.dylib: class `MetalDevice` declares `adapter` with the encoded type
      // `@"<MTLDevice>"`, alongside `queue`, `layer`, and `drawableHandle`.
      //
      // Still unpublished, so two things stand behind it. MacosMetalDeviceContractTest asks the
      // Objective-C runtime the same question at build time, so a Skiko upgrade that renames the
      // class or the property fails the build instead of blanking the map. And if it somehow got
      // past that, MacosObjectiveC checks class_respondsToSelector before resolving an
      // implementation, so the failure is an exception naming both rather than an aborted process.
      val adapter = MacosObjectiveC.sendPointer(metalDevice, SKIKO_METAL_DEVICE_ADAPTER)
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
