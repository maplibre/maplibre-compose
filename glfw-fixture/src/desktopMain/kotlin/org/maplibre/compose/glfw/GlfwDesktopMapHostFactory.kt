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
 * A [DesktopMapHostFactory] backed by a compose-glfw window's own graphics context, reaching into
 * no AWT or Skiko internals.
 *
 * Only the Metal path is implemented; the others are named rather than omitted so the diagnostic
 * says the bridge is unwritten instead of "no backend".
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
        // Deliberately empty rather than declared-and-failing: negotiation reports what each side
        // offered.
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
 * Keyed on the render context, which compose-glfw replaces when it rebuilds a window's graphics
 * stack, so that a new context recreates the host and drops every stale native handle. Note that
 * `HostWindow.renderContext` is a plain mutable field rather than Compose state, so a replacement
 * does not by itself recompose.
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
