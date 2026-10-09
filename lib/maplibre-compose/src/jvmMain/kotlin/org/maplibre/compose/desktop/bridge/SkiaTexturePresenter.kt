package org.maplibre.compose.desktop.bridge

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ContentChangeMode
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.MetalTextureTarget
import org.maplibre.compose.mlnffi.MlnFfiHostException
import org.maplibre.compose.mlnffi.MlnFfiMapDestination
import org.maplibre.compose.mlnffi.TextureOrigin

/**
 * Draws the textures MapLibre renders into the Compose scene. Each texture is wrapped once as a
 * Skia surface; [wrapper] says how to wrap one graphics API's textures.
 *
 * Every call must hold the host's exclusive access to Compose's Skia context, freeing included.
 * With OpenGL, that context must also be current.
 */
internal class SkiaTexturePresenter<T>(private val wrapper: SkiaTextureWrapper<T>) {
  private val presenters = mutableMapOf<Long, TexturePresenter>()

  fun draw(
    scope: DrawScope,
    skiaContext: DirectContext,
    target: T,
    destination: MlnFfiMapDestination,
    completion: ComposeFrameCompletion,
  ): Boolean {
    var drew = false
    scope.drawIntoCanvas { composeCanvas ->
      val presenter = presenters.getOrPut(wrapper.key(target)) { TexturePresenter() }
      presenter.draw(composeCanvas.skiaCanvas, skiaContext, target, destination)
      completion.frameRecorded(presenter::preserveFrame)
      drew = true
    }
    return drew
  }

  /** Detaches a completed image from external storage before Native can overwrite it again. */
  fun freeze(context: DirectContext, target: T): Image {
    val presenter = presenters.getOrPut(wrapper.key(target)) { TexturePresenter() }
    val image = presenter.snapshot(context, target)
    try {
      presenter.preserveFrame()
      context.flush()
      context.submit(syncCpu = true)
      return image
    } catch (error: Throwable) {
      image.close()
      throw error
    }
  }

  /** Drops the Skia wrapper for the texture [key] names; this must happen before it is freed. */
  fun forget(key: Long) {
    presenters.remove(key)?.close()
  }

  /** Drops every Skia wrapper. */
  fun closeAll() {
    val all = presenters.values.toList()
    presenters.clear()
    all.forEach { it.close() }
  }

  /**
   * Drops wrappers whose names belonged to a context that no longer exists, without freeing them.
   */
  fun abandonAll() {
    val all = presenters.values.toList()
    presenters.clear()
    all.forEach { it.abandon() }
  }

  private inner class TexturePresenter : AutoCloseable {
    private var layout: SkiaTextureLayout? = null
    private var renderTarget: WrappedRenderTarget? = null
    private var surface: Surface? = null

    fun draw(
      canvas: Canvas,
      context: DirectContext,
      target: T,
      destination: MlnFfiMapDestination,
    ) {
      snapshot(context, target).use { image ->
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

    fun snapshot(context: DirectContext, target: T): Image {
      val currentSurface = ensureSurface(context, target)
      wrapper.beforeDraw(context)
      currentSurface.notifyContentWillChange(ContentChangeMode.DISCARD)
      return currentSurface.makeImageSnapshot()
    }

    fun preserveFrame() {
      surface?.notifyContentWillChange(ContentChangeMode.RETAIN)
    }

    private fun ensureSurface(context: DirectContext, target: T): Surface {
      val nextLayout = wrapper.layout(target)
      surface?.let { if (layout == nextLayout) return it }

      close()
      layout = nextLayout
      val nextRenderTarget = wrapper.wrap(target, nextLayout).also { renderTarget = it }
      return Surface.makeFromBackendRenderTarget(
          context = context,
          rt = nextRenderTarget.renderTarget,
          origin = nextLayout.origin.toSkiaOrigin(),
          colorFormat = nextLayout.colorFormat,
          colorSpace = null,
          surfaceProps = null,
        )
        ?.also { surface = it }
        ?: throw MlnFfiHostException("Skia could not wrap ${nextRenderTarget.description}")
    }

    override fun close() {
      surface?.close()
      surface = null
      renderTarget?.close()
      renderTarget = null
      layout = null
    }

    fun abandon() {
      surface?.close()
      surface = null
      renderTarget?.abandon()
      renderTarget = null
      layout = null
    }
  }
}

/** How [SkiaTexturePresenter] wraps one graphics API's textures as Skia render targets. */
internal interface SkiaTextureWrapper<T> {
  /** Identifies the texture [target] names. The presenter keeps one Skia surface per texture. */
  fun key(target: T): Long

  /**
   * How Skia must see [target]'s texture. The presenter wraps the texture again when it changes.
   */
  fun layout(target: T): SkiaTextureLayout

  /** Wraps [target]'s texture as a render target matching [layout]. */
  fun wrap(target: T, layout: SkiaTextureLayout): WrappedRenderTarget

  /** Runs after the texture is wrapped and before each draw from it. */
  fun beforeDraw(context: DirectContext) {}
}

internal data class SkiaTextureLayout(
  val width: Int,
  val height: Int,
  /** Must match the texture's pixel format; a mismatch silently swaps channels. */
  val colorFormat: SurfaceColorFormat,
  val origin: TextureOrigin,
) {
  /** A texture allocated at [extent]'s physical size. */
  constructor(
    extent: MapExtent,
    colorFormat: SurfaceColorFormat,
    origin: TextureOrigin,
  ) : this(extent.physicalWidth, extent.physicalHeight, colorFormat, origin)
}

/**
 * A Skia render target over a texture, plus whatever the wrapper made to create it.
 *
 * @param description names the texture in errors, completing "Skia could not wrap …".
 * @param release frees what the wrapper made, after [renderTarget] is closed.
 */
internal class WrappedRenderTarget(
  val renderTarget: BackendRenderTarget,
  val description: String,
  private val release: () -> Unit = {},
) {
  fun close() {
    renderTarget.close()
    runCatching(release)
  }

  /** Closes the Skia object but not what [release] would free, which a lost context took along. */
  fun abandon() {
    renderTarget.close()
  }
}

/** Wraps MapLibre's `MTLTexture` directly. */
internal object MetalTextureWrapper : SkiaTextureWrapper<MetalTextureTarget> {
  override fun key(target: MetalTextureTarget): Long = target.texture.address

  // The host allocates BGRA8Unorm; anything else here silently swaps channels rather than erroring.
  override fun layout(target: MetalTextureTarget) =
    SkiaTextureLayout(target.extent, SurfaceColorFormat.BGRA_8888, target.origin)

  override fun wrap(target: MetalTextureTarget, layout: SkiaTextureLayout) =
    WrappedRenderTarget(
      BackendRenderTarget.makeMetal(
        width = layout.width,
        height = layout.height,
        texturePtr = target.texture.address,
      ),
      "Metal texture ${target.texture} as a render target",
    )
}

/** Wraps an `ID3D12Resource` texture directly. */
internal object Direct3DTextureWrapper : SkiaTextureWrapper<Direct3DTextureTarget> {
  override fun key(target: Direct3DTextureTarget): Long = target.texture.address

  override fun layout(target: Direct3DTextureTarget) =
    SkiaTextureLayout(target.extent, target.colorFormat, target.origin)

  override fun wrap(target: Direct3DTextureTarget, layout: SkiaTextureLayout) =
    WrappedRenderTarget(
      BackendRenderTarget.makeDirect3D(
        width = layout.width,
        height = layout.height,
        texturePtr = target.texture.address,
        format = target.format,
        sampleCnt = 1,
        levelCnt = 0,
      ),
      "Direct3D texture ${target.texture.address} as a render target",
    )
}
