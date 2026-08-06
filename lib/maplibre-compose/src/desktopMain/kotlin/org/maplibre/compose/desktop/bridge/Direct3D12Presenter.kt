package org.maplibre.compose.desktop.bridge

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ContentChangeMode
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.maplibre.compose.desktop.ComposeGpuHost
import org.maplibre.compose.mlnffi.MlnFfiHostException
import org.maplibre.compose.mlnffi.MlnFfiMapExtent
import org.maplibre.compose.mlnffi.NativeHandle
import org.maplibre.compose.mlnffi.TextureOrigin

/** `DXGI_FORMAT_B8G8R8A8_UNORM`, matching Compose's BGRA Direct3D 12 swap chain. */
internal const val DXGI_FORMAT_B8G8R8A8_UNORM: Int = 87

/**
 * How many snapshots to hold alive after handing them to Compose.
 *
 * Compose records draw commands and replays them later, so an image closed immediately after
 * `drawImageRect` can be sampled after it is gone.
 */
private const val RETAINED_IMAGE_COUNT = 8

/**
 * An `ID3D12Resource` texture to composite into Compose's scene.
 *
 * Presentation-only, and not a `MlnFfiRenderTarget`: MapLibre has no Direct3D backend to render
 * into one of these.
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
  val extent: MlnFfiMapExtent,
  /**
   * The [org.maplibre.compose.desktop.MlnFfiRenderTarget.generation] this texture corresponds to.
   *
   * Presenters are keyed by texture address, so a host that reallocates must call
   * [Direct3D12Presenter.forget] before releasing the old texture or a recycled address resolves to
   * a presenter wrapping freed memory.
   */
  val generation: Long,
)

/**
 * Draws a Direct3D 12 texture into Compose's Skia canvas on Windows.
 *
 * Compose owns the Direct3D device and Skia's [DirectContext]; this wraps the shared texture the
 * map was rendered into as a Skia surface and composites it.
 */
internal class Direct3D12Presenter(private val gpuHost: ComposeGpuHost) : AutoCloseable {
  private val presenters = mutableMapOf<Long, TexturePresenter>()

  fun draw(scope: DrawScope, skiaContext: DirectContext, target: Direct3DTextureTarget): Boolean {
    var drew = false
    scope.drawIntoCanvas { composeCanvas ->
      val presenter =
        presenters.getOrPut(target.texture.address) { TexturePresenter(target.texture) }
      presenter.draw(
        composeCanvas.skiaCanvas,
        skiaContext,
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
   * Forced onto the GPU thread, which owns the Skia objects wrapping it.
   */
  fun forget(texture: NativeHandle) {
    gpuHost.runOnGpuThread { presenters.remove(texture.address)?.close() }
  }

  override fun close() {
    gpuHost.runOnGpuThread {
      val all = presenters.values.toList()
      presenters.clear()
      all.forEach { it.close() }
    }
  }

  private class TexturePresenter(private val texture: NativeHandle) : AutoCloseable {
    private var contextIdentity = 0
    private var extent = MlnFfiMapExtent.Empty
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
          ?: throw MlnFfiHostException("Skia could not wrap Direct3D texture ${texture.address}")

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
          ?: throw MlnFfiHostException(
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
      extent = MlnFfiMapExtent.Empty
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
