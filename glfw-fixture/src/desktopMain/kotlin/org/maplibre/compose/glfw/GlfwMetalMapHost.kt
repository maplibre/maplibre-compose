package org.maplibre.compose.glfw

import androidx.compose.ui.graphics.drawscope.DrawScope
import dev.sargunv.composeglfw.MetalRenderContext
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
 * Bridges MapLibre's Metal rendering into a compose-glfw window on macOS. Same shape as the default
 * macOS host, but the `MTLDevice` and Skia `DirectContext` come from [MetalRenderContext], so there
 * is no reflection and no AWT event thread to route allocations around.
 *
 * Unfenced, like the default host: MapLibre's Metal texture backend commits and waits on its
 * command buffer inside `renderUpdate`.
 */
internal class GlfwMetalMapHost(renderContext: MetalRenderContext) : DesktopMapHost {
  private val device = renderContext.device
  private val rendererThread = GlfwRendererThread("maplibre-glfw-metal-renderer")
  private val presenter = GlfwMetalPresenter(renderContext.directContext)

  private var texture = NativeHandle(0L)
  private var pixelFormat = 0L
  private var generation = 0L
  private var currentExtent = DesktopMapExtent.Empty

  override val backends: DesktopBackendPair =
    DesktopBackendPair(MapRenderBackend.METAL, ComposeRenderBackend.METAL)

  override fun resize(extent: DesktopMapExtent) {
    val retiredTexture = rendererThread.run { resizeOnRendererThread(extent) }
    retiredTexture?.let(presenter::retire)
  }

  override fun acquireFrame(
    frameId: Long,
    extent: DesktopMapExtent,
    presentationTimeNanos: Long?,
  ): DesktopMapFrame {
    if (texture.isNull || extent != currentExtent) resize(extent)
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
      GlfwObjectiveC.runInAutoreleasePool(action)
    }

  override fun <T> withRendererAccess(action: () -> T): T = rendererThread.run(action)

  override fun draw(scope: DrawScope, target: DesktopRenderTarget): Boolean {
    if (target !is MetalTextureTarget || target.texture.isNull) return false
    return presenter.draw(scope, target)
  }

  override fun close() {
    try {
      // Retired rather than freed directly, so the Skia wrapper is dropped before its texture.
      takeTexture()?.let(presenter::retire)
      presenter.close()
    } finally {
      rendererThread.close()
    }
  }

  /**
   * Reallocates the texture for [extent], returning the one it replaced for the caller to retire.
   */
  private fun resizeOnRendererThread(extent: DesktopMapExtent): NativeHandle? {
    if (extent == currentExtent && !texture.isNull) return null

    val retiredTexture =
      if (extent.isEmpty) {
        takeTexture()
      } else {
        val oldTexture = texture
        val address =
          GlfwMetalTexture.create(
            device = device,
            oldTexture = oldTexture.address,
            width = extent.physicalWidth,
            height = extent.physicalHeight,
          )
        texture = NativeHandle(address)
        pixelFormat = GlfwMetalTexture.pixelFormat(address)
        // create() reuses the old texture when the physical size is unchanged; don't retire it.
        oldTexture.takeIf { !it.isNull && address != it.address }
      }

    currentExtent = extent
    generation += 1
    return retiredTexture
  }

  private fun target(extent: DesktopMapExtent, generation: Long): DesktopRenderTarget =
    MetalTextureTarget(
      texture = texture.takeIf { !it.isNull } ?: error("Metal texture allocation returned null"),
      pixelFormat = pixelFormat,
      origin = TextureOrigin.TOP_LEFT,
      extent = extent,
      generation = generation,
    )

  /** Clears the current texture, returning it for the caller to retire. */
  private fun takeTexture(): NativeHandle? {
    val retiredTexture = texture.takeIf { !it.isNull }
    texture = NativeHandle(0L)
    pixelFormat = 0L
    return retiredTexture
  }

  private class HostFrame(
    override val frameId: Long,
    override val extent: DesktopMapExtent,
    override val target: DesktopRenderTarget,
    override val presentationTimeNanos: Long?,
  ) : DesktopMapFrame
}
