package org.maplibre.compose.nucleus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.nucleusframework.window.tao.TaoGpuRenderContext
import dev.nucleusframework.window.tao.TaoMetalRenderContext
import dev.nucleusframework.window.tao.TaoOpenGlRenderContext
import dev.nucleusframework.window.tao.TaoRenderBackend
import dev.nucleusframework.window.tao.rememberTaoGpuRenderContext
import org.maplibre.compose.desktop.ComposeGpuContext
import org.maplibre.compose.desktop.ComposeMapHost
import org.maplibre.compose.desktop.MetalComposeGpuContext
import org.maplibre.compose.desktop.OpenGlComposeGpuContext
import org.maplibre.compose.desktop.OpenGlInterop
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.NativeHandle

/**
 * A [ComposeMapHost] over a Nucleus Tao surface's graphics context.
 *
 * Tao publishes the context that Compose uses, so this host passes it through unchanged.
 */
public class TaoComposeMapHost(private val renderContext: TaoGpuRenderContext) : ComposeMapHost {

  override val description: String
    get() = "the Nucleus Tao host on ${backend.name.lowercase()}"

  override val backend: ComposeRenderBackend
    get() =
      when (renderContext.backend) {
        TaoRenderBackend.METAL -> ComposeRenderBackend.METAL
        TaoRenderBackend.OPENGL -> ComposeRenderBackend.OPENGL
      }

  override val openGlInterop: OpenGlInterop
    get() =
      if (
        renderContext.backend == TaoRenderBackend.OPENGL &&
          System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
      ) {
        OpenGlInterop.ANGLE_D3D11
      } else {
        OpenGlInterop.NATIVE
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
          // Tao draws to the window itself, so the bind only makes the context current. The scoped
          // call restores the previous context and invalidates Skia's GL cache after the action.
          withContextCurrent = { action ->
            checkNotNull(context.withContextCurrent { action.run() }) {
              "$description could not make the GL context current"
            }
          },
        )
      // Tao keeps a private intermediate type in the sealed hierarchy. Public callers receive the
      // Metal and OpenGL leaves above.
      else ->
        error(
          "$description reported an unsupported TaoGpuRenderContext: ${context::class.simpleName}"
        )
    }

  override fun runOnGpuThread(action: Runnable) {
    renderContext.runOnGpuThread { action.run() }
  }
}

/**
 * The [ComposeMapHost] for the current Nucleus Tao surface, or null until it has a GPU context.
 *
 * Tao replaces the render context when it rebuilds a surface's graphics stack. Keying this host on
 * that context recreates the map bridge without retaining stale native handles.
 */
@Composable
public fun rememberTaoComposeMapHost(): ComposeMapHost? {
  val renderContext = rememberTaoGpuRenderContext() ?: return null
  return remember(renderContext) { TaoComposeMapHost(renderContext) }
}
