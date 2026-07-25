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
import org.maplibre.compose.desktop.NativeHandle
import org.maplibre.compose.desktop.TextureOrigin
import org.maplibre.compose.desktop.skiko.SkikoReflection.getField
import org.maplibre.compose.desktop.skiko.SkikoReflection.invokeDeclaredNoArg

/**
 * `DXGI_FORMAT_B8G8R8A8_UNORM`.
 *
 * Compose's Direct3D 12 swap chain is BGRA, so the shared texture is allocated BGRA too and both
 * sides agree on the channel order without a conversion pass.
 */
internal const val DXGI_FORMAT_B8G8R8A8_UNORM: Int = 87

/**
 * How many snapshots to hold alive after handing them to Compose.
 *
 * Compose records draw commands and replays them later, so an image closed immediately after
 * `drawImageRect` can be sampled after it is gone. Retaining a short ring keeps recorded frames
 * valid without unbounded growth.
 */
private const val RETAINED_IMAGE_COUNT = 8

/**
 * An `ID3D12Resource` texture to composite into Compose's scene.
 *
 * This is presentation-only and never reaches the map: MapLibre has no Direct3D backend, so nothing
 * on the producer side of the bridge can render into one of these. It is the *consumer* view of a
 * texture MapLibre rendered into through some other API, which is why it is not a
 * `DesktopRenderTarget`.
 */
internal data class Direct3DTextureTarget(
  /** `ID3D12Resource`. */
  val texture: NativeHandle,
  /** `DXGI_FORMAT` of [texture]. */
  val format: Int = DXGI_FORMAT_B8G8R8A8_UNORM,
  /** How Skia should interpret [format]. */
  val colorFormat: SurfaceColorFormat = SurfaceColorFormat.BGRA_8888,
  /** Row order of [texture]. */
  val origin: TextureOrigin = TextureOrigin.TOP_LEFT,
  /** The size [texture] was allocated at, which is what Skia has to wrap. */
  val extent: DesktopMapExtent,
  /**
   * The [org.maplibre.compose.desktop.DesktopRenderTarget.generation] this texture corresponds to.
   *
   * Presenters are keyed by texture address rather than by generation; a host that reallocates must
   * call [SkikoDirect3DPresenter.forget] before releasing the old texture, or a recycled address
   * would resolve to a presenter wrapping freed memory.
   */
  val generation: Long,
)

/**
 * Draws a Direct3D 12 texture into Compose's Skia canvas on Windows.
 *
 * Compose owns the Direct3D device and Skia's [DirectContext]; this wraps the shared texture the
 * map was rendered into as a Skia surface and composites it.
 */
internal object SkikoDirect3DPresenter {
  private val presenters = mutableMapOf<Long, TexturePresenter>()

  fun draw(scope: DrawScope, target: Direct3DTextureTarget): Boolean {
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

  /**
   * Drops the Skia wrapper for a texture, which must happen before the texture itself is released.
   *
   * Forced onto the AWT event thread because the Skia objects belong to the `DirectContext` that
   * thread owns. The example got this guarantee from its quit handler, which always closed through
   * the EDT; here the caller is a Compose `DisposableEffect`, whose applier thread is the EDT in
   * practice but is not guaranteed to be. `onEdt` short-circuits when already there.
   */
  fun forget(texture: NativeHandle) {
    SkikoReflection.onEdt { presenters.remove(texture.address)?.close() }
  }

  fun close() {
    SkikoReflection.onEdt {
      val all = presenters.values.toList()
      presenters.clear()
      all.forEach { it.close() }
    }
  }

  private fun findDirectContext(): DirectContext? = SkikoReflection.onEdt {
    val layer = SkikoReflection.requireSkiaLayer()
    val redrawer = SkikoReflection.requireRedrawer(layer, SkikoReflection.DIRECT3D_REDRAWER_CLASS)
    val handler =
      SkikoReflection.requireContextHandler(redrawer, SkikoReflection.DIRECT3D_REDRAWER_CLASS)
    (handler.getField("context") as? DirectContext)
      ?: run {
        handler.invokeDeclaredNoArg("initContext")
        // Unlike the OpenGL handler, whose accessor is `getContext`, Skiko's Direct3D handler
        // exposes only the protected factory `makeContext`. Calling it builds a second
        // DirectContext rather than returning Compose's, which is why it is the last resort.
        (handler.getField("context") as? DirectContext)
          ?: handler.invokeDeclaredNoArg("makeContext") as? DirectContext
      }
  }

  private class TexturePresenter(private val texture: NativeHandle) : AutoCloseable {
    private var contextIdentity = 0
    private var extent = DesktopMapExtent.Empty
    private var colorFormat = SurfaceColorFormat.BGRA_8888
    private var origin = TextureOrigin.TOP_LEFT
    private var renderTarget: BackendRenderTarget? = null
    private var surface: Surface? = null
    private val retainedImages = ArrayDeque<Image>()

    fun draw(
      canvas: org.jetbrains.skia.Canvas,
      context: DirectContext,
      target: Direct3DTextureTarget,
      destinationWidth: Float,
      destinationHeight: Float,
    ) {
      ensureSurface(context, target)
      val currentSurface =
        surface
          ?: throw DesktopHostException("Skia could not wrap Direct3D texture ${texture.address}")

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

    private fun ensureSurface(context: DirectContext, target: Direct3DTextureTarget) {
      val nextIdentity = System.identityHashCode(context)
      if (
        surface != null &&
          renderTarget != null &&
          contextIdentity == nextIdentity &&
          extent == target.extent &&
          colorFormat == target.colorFormat &&
          origin == target.origin
      ) {
        return
      }

      closeGpuResources()
      contextIdentity = nextIdentity
      extent = target.extent
      colorFormat = target.colorFormat
      origin = target.origin
      renderTarget =
        BackendRenderTarget.makeDirect3D(
          width = target.extent.physicalWidth,
          height = target.extent.physicalHeight,
          texturePtr = texture.address,
          format = target.format,
          sampleCnt = 1,
          levelCnt = 0,
        )
      surface =
        Surface.makeFromBackendRenderTarget(
          context = context,
          rt = checkNotNull(renderTarget),
          origin = target.origin.toSkiaOrigin(),
          colorFormat = target.colorFormat,
          colorSpace = null,
          surfaceProps = null,
        )
          ?: throw DesktopHostException(
            "Skia could not wrap Direct3D texture ${texture.address} as a render target"
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
      colorFormat = SurfaceColorFormat.BGRA_8888
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
