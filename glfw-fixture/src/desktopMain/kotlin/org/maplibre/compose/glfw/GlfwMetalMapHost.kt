package org.maplibre.compose.glfw

import androidx.compose.ui.graphics.drawscope.DrawScope
import dev.sargunv.composeglfw.MetalRenderContext
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
 * Bridges MapLibre's Metal rendering into a compose-glfw window on macOS.
 *
 * Structurally this is the same bridge the default host runs — allocate an `id<MTLTexture>` on the
 * device Compose draws with, let MapLibre render into it, wrap it as a Skia surface to composite it
 * — because that is what Metal-to-Metal is. What differs is where the two ends come from: both the
 * `MTLDevice` and Skia's `DirectContext` arrive as fields of compose-glfw's [MetalRenderContext],
 * so nothing here reflects into anything, and nothing here knows what a `ComposeWindow` is.
 *
 * The other visible difference is what is missing. The default host has to route every allocation
 * and every release around the AWT event thread, because reading Skiko's device and dropping a Skia
 * wrapper both block on it, and the renderer thread is usually the thread the event thread is
 * waiting for. None of that applies here, so `resize` is one hop instead of two and there is no
 * deadlock to avoid.
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

  override val capabilities: DesktopHostCapabilities =
    DesktopHostCapabilities(
      backends = backends,
      // False for the same measured reason the default macOS host reports false: this bridge
      // inserts no fence or event of its own. MapLibre's Metal texture backend commits its command
      // buffer and waits on it from inside renderUpdate, so the texture is finished before
      // withProducerAccess returns and draw() can sample it — but that ordering is the producer's
      // doing, not something this host signals, and this flag reports what the host does.
      supportsExplicitSynchronization = false,
      // A texture cannot change size, so any extent change produces a new generation. The session
      // reads that and retargets the live render session rather than re-attaching, as long as the
      // scale factor held, which is what keeps the tile pyramid across a drag resize.
      supportsResizeWithoutRecreate = false,
    )

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
   * Runs [action] on the renderer thread, inside an autorelease pool.
   *
   * MapLibre's Metal backend returns autoreleased command buffers and encoders from `renderUpdate`,
   * and the FFI documentation requires that call to happen inside a pool. The renderer thread is
   * one this fixture created, so it has none of its own.
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
      // Retired rather than freed directly, so that the Skia wrappers and the textures they wrap go
      // in the one order that is safe, through the one path that knows it.
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
        // The allocation is skipped when the physical size did not change, in which case the old
        // texture comes back and must not be retired. The generation still advances below, because
        // the logical extent the session renders with did change.
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
      // Metal textures are addressed from the top left, and this host allocates this one itself, so
      // the row order is never in question.
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
