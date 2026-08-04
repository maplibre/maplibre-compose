package org.maplibre.compose.glfw

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.sargunv.composeglfw.Direct3DRenderContext
import dev.sargunv.composeglfw.LocalWindow
import dev.sargunv.composeglfw.MetalRenderContext
import dev.sargunv.composeglfw.OpenGlRenderContext
import dev.sargunv.composeglfw.RenderContext
import org.maplibre.compose.desktop.ComposeRenderBackend
import org.maplibre.compose.desktop.DesktopBackendPair
import org.maplibre.compose.desktop.DesktopMapHostFactory
import org.maplibre.compose.desktop.DesktopMapHostResult
import org.maplibre.compose.desktop.MapRenderBackend

/**
 * A [DesktopMapHostFactory] backed by a compose-glfw window's own graphics context.
 *
 * The whole factory is a [RenderContext] and a `when` over its three subtypes. That is the claim
 * the SPI makes and this fixture is here to check: a host supplies GPU objects and a way to draw
 * them, and the map does not care where they came from. Nothing below this line reaches into AWT,
 * Skiko internals, or `ComposeWindow`.
 *
 * Only the Metal path is implemented. The two others are named rather than omitted so the
 * diagnostic a Linux or Windows user gets says "this fixture has not written that bridge yet"
 * instead of "no backend".
 */
public class GlfwDesktopMapHostFactory(private val renderContext: RenderContext) :
  DesktopMapHostFactory {

  override val description: String
    get() = "the compose-glfw host on ${renderContext.backendName}"

  override val supportedBackends: Set<DesktopBackendPair>
    get() =
      when (renderContext) {
        is MetalRenderContext ->
          setOf(DesktopBackendPair(MapRenderBackend.METAL, ComposeRenderBackend.METAL))
        // Deliberately empty rather than declared-and-failing: negotiation prints what each side
        // offered, and claiming a pair this fixture cannot build would make that report a lie.
        is OpenGlRenderContext,
        is Direct3DRenderContext -> emptySet()
      }

  override fun create(producer: MapRenderBackend): DesktopMapHostResult =
    when (renderContext) {
      is MetalRenderContext ->
        if (producer != MapRenderBackend.METAL) {
          DesktopMapHostResult.Unsupported("$description cannot bridge $producer to Metal.")
        } else {
          try {
            DesktopMapHostResult.Created(GlfwMetalMapHost(renderContext))
          } catch (error: Throwable) {
            if (error is VirtualMachineError) throw error
            DesktopMapHostResult.Failed("$description failed to create a Metal bridge", error)
          }
        }
      is OpenGlRenderContext,
      is Direct3DRenderContext ->
        DesktopMapHostResult.Unsupported(
          "$description has no MapLibre bridge in this fixture; only the macOS Metal path is " +
            "implemented."
        )
    }
}

/**
 * The [DesktopMapHostFactory] for the compose-glfw window this composable is running in.
 *
 * Keyed on the render context rather than remembered unconditionally, because compose-glfw replaces
 * it when it has to rebuild a window's graphics stack. That is the one place where the SPI's
 * "changing the factory recreates the host" rule does the work: a new context produces a new
 * factory, which produces a new host, which drops every stale native handle at once.
 *
 * The catch, and it is a compose-glfw gap rather than an SPI one, is that
 * `HostWindow.renderContext` is a plain mutable field rather than Compose state, so a replacement
 * does not by itself recompose. Nothing in this fixture has provoked one.
 */
@Composable
public fun rememberGlfwDesktopMapHostFactory(): DesktopMapHostFactory {
  val renderContext = LocalWindow.current.renderContext
  return remember(renderContext) { GlfwDesktopMapHostFactory(renderContext) }
}

private val RenderContext.backendName: String
  get() =
    when (this) {
      is MetalRenderContext -> "Metal"
      is OpenGlRenderContext -> "OpenGL"
      is Direct3DRenderContext -> "Direct3D"
    }
