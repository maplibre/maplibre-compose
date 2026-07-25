package org.maplibre.compose.desktop.skiko

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
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
import org.maplibre.compose.desktop.skiko.SkikoReflection.getField
import org.maplibre.compose.desktop.skiko.SkikoReflection.invokeDeclaredNoArg

/**
 * How many snapshots to hold alive after handing them to Compose.
 *
 * Compose records draw commands and replays them later, so an image closed immediately after
 * `drawImageRect` can be sampled after it is gone. Retaining a short ring keeps recorded frames
 * valid without unbounded growth.
 */
private const val RETAINED_IMAGE_COUNT = 8

/**
 * Draws MapLibre's Metal texture into Compose's Skia canvas on macOS.
 *
 * Compose owns the Metal device and Skia's [DirectContext]; MapLibre rendered into a texture
 * allocated on that same device, so presenting is only a matter of wrapping the texture as a Skia
 * surface and compositing it. There is no import, no copy, and no context to make current — Metal
 * objects are not bound to a thread the way OpenGL contexts are.
 *
 * Ported from the `maplibre-native-ffi` Compose example, which is the reference for this path.
 */
internal object SkikoMetalPresenter {
  private val presenters = mutableMapOf<Long, TexturePresenter>()

  fun draw(scope: DrawScope, target: MetalTextureTarget): Boolean {
    var drew = false
    scope.drawIntoCanvas { composeCanvas ->
      val context = findDirectContext() ?: return@drawIntoCanvas
      val presenter =
        presenters.getOrPut(target.texture.address) { TexturePresenter(target.texture) }
      presenter.draw(
        composeCanvas.nativeCanvas,
        context,
        target,
        scope.size.width,
        scope.size.height,
      )
      drew = true
    }
    return drew
  }

  /** Drops the Skia wrapper for [texture], which must happen before the texture is released. */
  fun forget(texture: NativeHandle) {
    presenters.remove(texture.address)?.close()
  }

  fun close() {
    val all = presenters.values.toList()
    presenters.clear()
    all.forEach { it.close() }
  }

  private fun findDirectContext(): DirectContext? = SkikoReflection.onEdt {
    val handler = SkikoReflection.requireMetalContextHandler(SkikoReflection.requireSkiaLayer())
    (handler.getField("context") as? DirectContext)
      ?: run {
        handler.invokeDeclaredNoArg("initContext")
        (handler.getField("context") as? DirectContext)
          ?: handler.invokeDeclaredNoArg("getContext") as? DirectContext
      }
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
      val currentSurface =
        surface ?: throw DesktopHostException("Skia could not wrap Metal texture ${target.texture}")

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
          origin = origin.toSkiaOrigin(),
          // The host allocates BGRA8Unorm, which is Metal's native layer format; asking Skia for
          // anything else here silently produces swapped channels rather than an error.
          colorFormat = SurfaceColorFormat.BGRA_8888,
          colorSpace = null,
          surfaceProps = null,
        )
          ?: throw DesktopHostException(
            "Skia could not wrap Metal texture ${target.texture} as a render target"
          )
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

private fun TextureOrigin.toSkiaOrigin(): SurfaceOrigin =
  when (this) {
    TextureOrigin.TOP_LEFT -> SurfaceOrigin.TOP_LEFT
    TextureOrigin.BOTTOM_LEFT -> SurfaceOrigin.BOTTOM_LEFT
  }
