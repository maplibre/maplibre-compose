package org.maplibre.compose.glfw

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import java.util.concurrent.ConcurrentLinkedQueue
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ContentChangeMode
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.maplibre.compose.desktop.DesktopMapExtent
import org.maplibre.compose.desktop.MetalTextureTarget
import org.maplibre.compose.desktop.NativeHandle
import org.maplibre.compose.desktop.TextureOrigin

/**
 * How many snapshots to hold alive after handing them to Compose.
 *
 * Compose records draw commands and replays them later, so an image closed immediately after
 * `drawImageRect` can be sampled after it is gone. Retaining a short ring keeps recorded frames
 * valid without unbounded growth.
 */
private const val RETAINED_IMAGE_COUNT = 8

/**
 * Draws MapLibre's Metal texture into the Compose scene compose-glfw is rendering.
 *
 * The default host has to find Skia's [DirectContext] by reflecting into a `SkiaLayer` on the AWT
 * event thread, and then hop back to that thread every time it wants to free a Skia object.
 * compose-glfw hands the same [DirectContext] over as a field of `MetalRenderContext`, so this
 * class is handed it at construction and never asks the host anything again — which is the single
 * biggest difference between the two bridges, and the reason this one is roughly half the size.
 *
 * Not thread-safe by design: everything here runs on the GLFW main thread, which is the only thread
 * compose-glfw renders its scene from and therefore the only one that may touch the context.
 */
internal class GlfwMetalPresenter(private val context: DirectContext) : AutoCloseable {
  private val presenters = mutableMapOf<Long, TexturePresenter>()

  /**
   * Textures whose Skia wrappers are still alive, waiting for a thread that may free them.
   *
   * A queue rather than a direct call because a texture is retired by the host's renderer thread,
   * during a resize, and Skia objects belong to whichever thread owns the [DirectContext]. The
   * default host solves the same problem by hopping to the AWT event thread; compose-glfw exposes
   * no equivalent "run this on the host thread" hook, so the work is deferred to the next draw
   * instead. There is always a next draw: a resize is followed by a frame request.
   */
  private val retired = ConcurrentLinkedQueue<Long>()

  fun draw(scope: DrawScope, target: MetalTextureTarget): Boolean {
    releaseRetired(keepAlive = target.texture.address)
    var drew = false
    scope.drawIntoCanvas { composeCanvas ->
      val presenter =
        presenters.getOrPut(target.texture.address) { TexturePresenter(target.texture) }
      presenter.draw(composeCanvas.skiaCanvas, context, target, scope.size.width, scope.size.height)
      drew = true
    }
    return drew
  }

  /** Hands a texture back once nothing will render into it again. */
  fun retire(texture: NativeHandle) {
    if (!texture.isNull) retired.add(texture.address)
  }

  override fun close() {
    releaseRetired(keepAlive = 0L)
    val all = presenters.values.toList()
    presenters.clear()
    all.forEach { it.close() }
  }

  /**
   * Frees retired textures, except one the caller is about to draw.
   *
   * The exception is the rule `DesktopRenderTarget.generation` now states, and this fixture is what
   * put it there. The surface keeps presenting the last target that was rendered into while
   * MapLibre catches up with a new size, so a texture retired inside `acquireFrame` can be handed
   * straight back to this method in the same frame. Freeing it on the generation bump, which is
   * what this host did first, means `BackendRenderTarget.makeMetal` calls `CFRetain` on a released
   * `MTLTexture` and traps — `EXC_BREAKPOINT` on the main thread, within a minute of a session that
   * has a couple of window resizes in it.
   *
   * Deferring costs one extra texture and cannot loop: the next target with a different address
   * releases it.
   */
  private fun releaseRetired(keepAlive: Long) {
    if (retired.isEmpty()) return
    var deferred: Long? = null
    while (true) {
      val address = retired.poll() ?: break
      if (address == keepAlive) {
        deferred = address
        continue
      }
      // Order matters: Skia holds a surface wrapping this texture, so that has to go first.
      presenters.remove(address)?.close()
      GlfwMetalTexture.dispose(address)
    }
    deferred?.let(retired::add)
  }

  private class TexturePresenter(private val texture: NativeHandle) : AutoCloseable {
    private var contextIdentity = 0
    private var extent = DesktopMapExtent.Empty
    private var origin = TextureOrigin.TOP_LEFT
    private var renderTarget: BackendRenderTarget? = null
    private var surface: Surface? = null
    private val retainedImages = ArrayDeque<Image>()

    fun draw(
      canvas: org.jetbrains.skia.Canvas,
      context: DirectContext,
      target: MetalTextureTarget,
      destinationWidth: Float,
      destinationHeight: Float,
    ) {
      ensureSurface(context, target)
      val currentSurface = surface ?: error("Skia could not wrap Metal texture ${target.texture}")

      // MapLibre overwrote every pixel; telling Skia the old contents are gone lets it skip
      // reloading them into its own render pass.
      currentSurface.notifyContentWillChange(ContentChangeMode.DISCARD)
      val image = currentSurface.makeImageSnapshot()
      retain(image)
      canvas.drawImageRect(
        image = image,
        src = Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
        dst = Rect.makeWH(destinationWidth, destinationHeight),
        samplingMode = SamplingMode.LINEAR,
        paint = null,
        strict = true,
      )
    }

    private fun ensureSurface(context: DirectContext, target: MetalTextureTarget) {
      val nextIdentity = System.identityHashCode(context)
      if (
        surface != null &&
          renderTarget != null &&
          contextIdentity == nextIdentity &&
          extent == target.extent &&
          origin == target.origin
      ) {
        return
      }

      closeGpuResources()
      contextIdentity = nextIdentity
      extent = target.extent
      origin = target.origin
      renderTarget =
        BackendRenderTarget.makeMetal(
          width = target.extent.physicalWidth,
          height = target.extent.physicalHeight,
          texturePtr = texture.address,
        )
      surface =
        Surface.makeFromBackendRenderTarget(
          context = context,
          rt = checkNotNull(renderTarget),
          origin =
            when (origin) {
              TextureOrigin.TOP_LEFT -> SurfaceOrigin.TOP_LEFT
              TextureOrigin.BOTTOM_LEFT -> SurfaceOrigin.BOTTOM_LEFT
            },
          // The host allocates BGRA8Unorm, which is Metal's native layer format; asking Skia for
          // anything else here silently produces swapped channels rather than an error.
          colorFormat = SurfaceColorFormat.BGRA_8888,
          colorSpace = null,
          surfaceProps = null,
        ) ?: error("Skia could not wrap Metal texture ${target.texture} as a render target")
    }

    private fun retain(image: Image) {
      retainedImages.addLast(image)
      while (retainedImages.size > RETAINED_IMAGE_COUNT) retainedImages.removeFirst().close()
    }

    override fun close() {
      closeGpuResources()
      contextIdentity = 0
      extent = DesktopMapExtent.Empty
      origin = TextureOrigin.TOP_LEFT
    }

    private fun closeGpuResources() {
      while (retainedImages.isNotEmpty()) retainedImages.removeFirst().close()
      surface?.close()
      surface = null
      renderTarget?.close()
      renderTarget = null
    }
  }
}
