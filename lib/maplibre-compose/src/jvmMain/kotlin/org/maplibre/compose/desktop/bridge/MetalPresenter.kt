package org.maplibre.compose.desktop.bridge

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import java.util.concurrent.ConcurrentLinkedQueue
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ContentChangeMode
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.maplibre.compose.desktop.ComposeMapHost
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.MetalTextureTarget
import org.maplibre.compose.mlnffi.MlnFfiHostException
import org.maplibre.compose.mlnffi.NativeHandle
import org.maplibre.compose.mlnffi.TextureOrigin

/**
 * Draws MapLibre's Metal texture into the Compose scene by wrapping it as a Skia surface. Every
 * Skia object here is accessed inside the host's exclusive context boundary, freeing included.
 */
internal class MetalPresenter(private val gpuHost: ComposeMapHost) : AutoCloseable {
  private val presenters = mutableMapOf<Long, TexturePresenter>()

  /** Textures whose Skia wrappers are still alive, waiting for a thread that may free them. */
  private val retired = ConcurrentLinkedQueue<Long>()

  fun draw(
    scope: DrawScope,
    skiaContext: DirectContext,
    target: MetalTextureTarget,
    completion: ComposeFrameCompletion,
  ): Boolean {
    releaseRetired(keepAlive = target.texture.address)
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
      completion.frameRecorded(presenter::preserveFrame)
      drew = true
    }
    return drew
  }

  /** Hands a texture back once nothing will render into it again. Safe from any thread. */
  fun retire(texture: NativeHandle) {
    if (!texture.isNull) retired.add(texture.address)
  }

  /** Releases wrappers created by a Skia context that the host replaced. */
  fun resetContext() {
    gpuHost.runOnGpuThread { closePresenters() }
  }

  override fun close() {
    gpuHost.runOnGpuThread {
      releaseRetired(keepAlive = 0L)
      closePresenters()
    }
  }

  private fun closePresenters() {
    val all = presenters.values.toList()
    presenters.clear()
    all.forEach { it.close() }
  }

  /**
   * Frees retired textures, except one the caller is about to draw: a texture retired inside
   * `acquireFrame` can be presented again in the same frame, and freeing it early makes
   * `BackendRenderTarget.makeMetal` `CFRetain` a released `MTLTexture` and trap.
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
      MetalTexture.dispose(address)
    }
    deferred?.let(retired::add)
  }

  private class TexturePresenter(private val texture: NativeHandle) : AutoCloseable {
    private var extent = MapExtent.Empty
    private var origin = TextureOrigin.TOP_LEFT
    private var renderTarget: BackendRenderTarget? = null
    private var surface: Surface? = null

    fun draw(
      canvas: org.jetbrains.skia.Canvas,
      context: DirectContext,
      target: MetalTextureTarget,
      destinationWidth: Float,
      destinationHeight: Float,
    ) {
      ensureSurface(context, target)
      val currentSurface =
        surface ?: throw MlnFfiHostException("Skia could not wrap Metal texture ${target.texture}")

      currentSurface.notifyContentWillChange(ContentChangeMode.DISCARD)
      currentSurface.makeImageSnapshot().use { image ->
        canvas.drawImageRect(
          image = image,
          src = Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
          dst = Rect.makeWH(destinationWidth, destinationHeight),
          samplingMode = SamplingMode.LINEAR,
          paint = null,
          strict = true,
        )
      }
    }

    fun preserveFrame() {
      surface?.notifyContentWillChange(ContentChangeMode.RETAIN)
    }

    private fun ensureSurface(context: DirectContext, target: MetalTextureTarget) {
      if (
        surface != null &&
          renderTarget != null &&
          extent == target.extent &&
          origin == target.origin
      ) {
        return
      }

      closeGpuResources()
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
          // The host allocates BGRA8Unorm; anything else here silently swaps channels rather than
          // erroring.
          colorFormat = SurfaceColorFormat.BGRA_8888,
          colorSpace = null,
          surfaceProps = null,
        )
          ?: throw MlnFfiHostException(
            "Skia could not wrap Metal texture ${target.texture} as a render target"
          )
    }

    override fun close() {
      closeGpuResources()
      extent = MapExtent.Empty
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
