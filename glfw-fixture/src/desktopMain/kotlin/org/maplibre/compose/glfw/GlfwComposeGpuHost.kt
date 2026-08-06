package org.maplibre.compose.glfw

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.sargunv.composeglfw.Direct3DRenderContext
import dev.sargunv.composeglfw.LocalWindow
import dev.sargunv.composeglfw.MetalRenderContext
import dev.sargunv.composeglfw.OpenGlRenderContext
import dev.sargunv.composeglfw.RenderContext
import org.maplibre.compose.desktop.ComposeGpuContext
import org.maplibre.compose.desktop.ComposeRenderBackend
import org.maplibre.compose.desktop.DesktopComposeGpuHost
import org.maplibre.compose.desktop.Direct3D12ComposeGpuContext
import org.maplibre.compose.desktop.MetalComposeGpuContext
import org.maplibre.compose.desktop.NativeHandle
import org.maplibre.compose.desktop.OpenGlComposeGpuContext

/**
 * A [DesktopComposeGpuHost] over a compose-glfw window's own graphics context.
 *
 * compose-glfw publishes what Compose draws with, so this hands it over unchanged: no AWT, no
 * Skiko, and no reflection.
 */
public class GlfwComposeGpuHost(private val renderContext: RenderContext) : DesktopComposeGpuHost {

  /**
   * The thread compose-glfw renders from, which on macOS is the process's first thread. Captured at
   * construction, since this is built during composition on that thread.
   */
  private val uiThread: Thread = Thread.currentThread()

  override val description: String
    get() = "the compose-glfw host on ${backend.name.lowercase()}"

  override val backend: ComposeRenderBackend
    get() =
      when (renderContext) {
        is MetalRenderContext -> ComposeRenderBackend.METAL
        is OpenGlRenderContext -> ComposeRenderBackend.OPENGL
        is Direct3DRenderContext -> ComposeRenderBackend.DIRECT3D12
      }

  override fun gpuContext(): ComposeGpuContext =
    when (renderContext) {
      is MetalRenderContext ->
        MetalComposeGpuContext(
          skiaContext = renderContext.directContext,
          device = NativeHandle(renderContext.device),
        )
      is OpenGlRenderContext ->
        OpenGlComposeGpuContext(
          skiaContext = renderContext.directContext,
          // compose-glfw draws to the window itself, so nothing has to be locked around the
          // context the way an AWT drawing surface does.
          withContextCurrent = { action ->
            renderContext.makeCurrent()
            action.run()
          },
        )
      is Direct3DRenderContext ->
        Direct3D12ComposeGpuContext(
          skiaContext = renderContext.directContext,
          device = NativeHandle(renderContext.device),
        )
    }

  override fun runOnGpuThread(action: Runnable) {
    // An assertion rather than a dispatch: MapLibre Compose only asks for GPU-thread work from the
    // draw pass and from disposal, both of which compose-glfw already runs here.
    check(Thread.currentThread() === uiThread) {
      "$description was asked for GPU work from ${Thread.currentThread().name}, not the GLFW " +
        "thread it renders on."
    }
    action.run()
  }
}

/**
 * The [DesktopComposeGpuHost] for the compose-glfw window this composable is running in.
 *
 * Keyed on the render context, which compose-glfw replaces when it rebuilds a window's graphics
 * stack, so that a new context recreates the map's bridge and drops every stale native handle. Note
 * that `HostWindow.renderContext` is a plain mutable field rather than Compose state, so a
 * replacement does not by itself recompose.
 */
@Composable
public fun rememberGlfwComposeGpuHost(): DesktopComposeGpuHost {
  val renderContext = LocalWindow.current.renderContext
  return remember(renderContext) { GlfwComposeGpuHost(renderContext) }
}
