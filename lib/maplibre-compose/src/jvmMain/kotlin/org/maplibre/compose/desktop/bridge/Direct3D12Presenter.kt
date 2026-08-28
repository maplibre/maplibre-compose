package org.maplibre.compose.desktop.bridge

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ContentChangeMode
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.maplibre.compose.desktop.ComposeMapHost
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.MlnFfiHostException
import org.maplibre.compose.mlnffi.MlnFfiMapDestination
import org.maplibre.compose.mlnffi.NativeHandle
import org.maplibre.compose.mlnffi.TextureOrigin

internal const val DXGI_FORMAT_B8G8R8A8_UNORM: Int = 87

/** An `ID3D12Resource` texture to composite into Compose's scene. */
internal data class Direct3DTextureTarget(
  /** `ID3D12Resource`. */
  val texture: NativeHandle,
  /** `DXGI_FORMAT` of [texture]. */
  val format: Int = DXGI_FORMAT_B8G8R8A8_UNORM,
  /** How Skia should interpret [format]. */
  val colorFormat: SurfaceColorFormat = SurfaceColorFormat.BGRA_8888,
  /** Row order of [texture]. */
  val origin: TextureOrigin = TextureOrigin.TOP_LEFT,
  /** The size [texture] was allocated at. */
  val extent: MapExtent,
  /**
   * The [org.maplibre.compose.desktop.MlnFfiRenderTarget.generation] this texture corresponds to.
   */
  val generation: Long,
)

/** Draws a Direct3D 12 texture into Compose's Skia canvas on Windows. */
internal class Direct3D12Presenter(private val gpuHost: ComposeMapHost) : AutoCloseable {
  private val presenters = mutableMapOf<Long, TexturePresenter>()

  fun draw(
    scope: DrawScope,
    skiaContext: DirectContext,
    target: Direct3DTextureTarget,
    destination: MlnFfiMapDestination,
    completion: ComposeFrameCompletion,
  ): Boolean {
    var drew = false
    scope.drawIntoCanvas { composeCanvas ->
      val presenter =
        presenters.getOrPut(target.texture.address) { TexturePresenter(target.texture) }
      presenter.draw(
        composeCanvas.skiaCanvas,
        skiaContext,
        target,
        destination,
      )
      completion.frameRecorded(presenter::preserveFrame)
      drew = true
    }
    return drew
  }

  /** Drops the Skia wrapper for a texture; must happen before the texture itself is released. */
  fun forget(texture: NativeHandle) {
    gpuHost.runOnGpuThread { presenters.remove(texture.address)?.close() }
  }

  /** Releases wrappers created by a Skia context that the host replaced. */
  fun resetContext() {
    gpuHost.runOnGpuThread(::closePresenters)
  }

  override fun close() {
    gpuHost.runOnGpuThread(::closePresenters)
  }

  private fun closePresenters() {
    val all = presenters.values.toList()
    presenters.clear()
    all.forEach { it.close() }
  }

  private class TexturePresenter(private val texture: NativeHandle) : AutoCloseable {
    private var extent = MapExtent.Empty
    private var colorFormat = SurfaceColorFormat.BGRA_8888
    private var origin = TextureOrigin.TOP_LEFT
    private var renderTarget: BackendRenderTarget? = null
    private var surface: Surface? = null

    fun draw(
      canvas: org.jetbrains.skia.Canvas,
      context: DirectContext,
      target: Direct3DTextureTarget,
      destination: MlnFfiMapDestination,
    ) {
      ensureSurface(context, target)
      val currentSurface =
        surface
          ?: throw MlnFfiHostException("Skia could not wrap Direct3D texture ${texture.address}")

      currentSurface.notifyContentWillChange(ContentChangeMode.DISCARD)
      currentSurface.makeImageSnapshot().use { image ->
        canvas.drawImageRect(
          image = image,
          src = Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
          dst =
            Rect.makeLTRB(
              destination.left.toFloat(),
              destination.top.toFloat(),
              destination.right.toFloat(),
              destination.bottom.toFloat(),
            ),
          samplingMode = SamplingMode.LINEAR,
          paint = null,
          strict = true,
        )
      }
    }

    fun preserveFrame() {
      surface?.notifyContentWillChange(ContentChangeMode.RETAIN)
    }

    private fun ensureSurface(context: DirectContext, target: Direct3DTextureTarget) {
      if (
        surface != null &&
          renderTarget != null &&
          extent == target.extent &&
          colorFormat == target.colorFormat &&
          origin == target.origin
      ) {
        return
      }

      closeGpuResources()
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

    override fun close() {
      closeGpuResources()
      extent = MapExtent.Empty
      colorFormat = SurfaceColorFormat.BGRA_8888
      origin = TextureOrigin.TOP_LEFT
    }

    private fun closeGpuResources() {
      surface?.close()
      surface = null
      renderTarget?.close()
      renderTarget = null
    }
  }
}
