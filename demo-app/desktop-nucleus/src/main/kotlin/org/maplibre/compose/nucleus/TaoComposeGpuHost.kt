package org.maplibre.compose.nucleus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.nucleusframework.window.tao.TaoGpuRenderContext
import dev.nucleusframework.window.tao.TaoMetalRenderContext
import dev.nucleusframework.window.tao.TaoOpenGlRenderContext
import dev.nucleusframework.window.tao.TaoRenderBackend
import dev.nucleusframework.window.tao.rememberTaoGpuRenderContext
import org.maplibre.compose.desktop.ComposeGpuContext
import org.maplibre.compose.desktop.ComposeGpuHost
import org.maplibre.compose.desktop.MetalComposeGpuContext
import org.maplibre.compose.desktop.OpenGlComposeGpuContext
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.NativeHandle

/**
 * A [ComposeGpuHost] over a Nucleus Tao surface's own graphics context.
 *
 * Tao publishes what Compose requires through [TaoGpuRenderContext], so this hands it over
 * unchanged.
 */
public class TaoComposeGpuHost(private val renderContext: TaoGpuRenderContext) : ComposeGpuHost {

  override val description: String
    get() = "the Nucleus Tao host on ${backend.name.lowercase()}"

  override val backend: ComposeRenderBackend
    get() =
      when (renderContext.backend) {
        TaoRenderBackend.METAL -> ComposeRenderBackend.METAL
        TaoRenderBackend.OPENGL -> ComposeRenderBackend.OPENGL
      }

  override fun gpuContext(): ComposeGpuContext =
    when (val context = renderContext) {
      is TaoMetalRenderContext ->
        MetalComposeGpuContext(
          skiaContext = context.skiaContext,
          device = NativeHandle(context.metalDevicePtr),
        )
      is TaoOpenGlRenderContext ->
        OpenGlComposeGpuContext(
          skiaContext = context.skiaContext,
          // Tao draws to the window itself, so the bind is just make-current; nesting restores the
          // previous context and invalidates Skia's GL cache for us.
          withContextCurrent = { action ->
            checkNotNull(context.withContextCurrent { action.run() }) {
              "$description could not make the GL context current"
            }
          },
        )
      // Tao keeps a private intermediate type in the sealed hierarchy; public callers only see the
      // Metal and OpenGL leaves above.
      else ->
        error(
          "$description reported an unsupported TaoGpuRenderContext: ${context::class.simpleName}"
        )
    }

  override fun runOnGpuThread(action: Runnable) {
    renderContext.runOnGpuThread {
      action.run()
    }
  }
}

/**
 * The [ComposeGpuHost] for the Nucleus Tao surface this composable is running in, or null while
 * that surface has no GPU context yet.
 *
 * Keyed on the render context, which Tao replaces when it rebuilds a surface's graphics stack, so
 * that a new context recreates the map's bridge and drops every stale native handle.
 */
@Composable
public fun rememberTaoComposeGpuHost(): ComposeGpuHost? {
  val renderContext = rememberTaoGpuRenderContext() ?: return null
  return remember(renderContext) { TaoComposeGpuHost(renderContext) }
}
