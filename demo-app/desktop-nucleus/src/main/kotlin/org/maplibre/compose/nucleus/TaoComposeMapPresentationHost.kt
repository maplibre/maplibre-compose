package org.maplibre.compose.nucleus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.nucleusframework.window.tao.TaoGpuRenderContext
import dev.nucleusframework.window.tao.TaoMetalRenderContext
import dev.nucleusframework.window.tao.TaoOpenGlRenderContext
import dev.nucleusframework.window.tao.rememberTaoGpuRenderContext
import org.maplibre.compose.desktop.ComposeMapPresentationHost
import org.maplibre.compose.desktop.MetalComposeGpuContext
import org.maplibre.compose.desktop.OpenGlComposeGpuContext
import org.maplibre.compose.mlnffi.NativeHandle
import org.maplibre.compose.util.ExperimentalMaplibreComposeApi

/** Adapts the current Tao context to the corresponding typed window integration. */
@OptIn(ExperimentalMaplibreComposeApi::class)
private fun taoComposeMapPresentationHost(
  renderContext: TaoGpuRenderContext
): ComposeMapPresentationHost {
  val runOnGpuThread: (Runnable) -> Unit = { action ->
    renderContext.runOnGpuThread { action.run() }
  }
  return when (renderContext) {
    is TaoMetalRenderContext ->
      ComposeMapPresentationHost.macosMetal(
        description = "the Nucleus Tao Metal host",
        gpuContext = renderContext.metalGpuContext(),
        runOnGpuThread = runOnGpuThread,
      )
    is TaoOpenGlRenderContext if isWindows ->
      ComposeMapPresentationHost.windowsAngle(
        description = OPEN_GL_DESCRIPTION,
        gpuContext = renderContext.openGlGpuContext(),
        runOnGpuThread = runOnGpuThread,
      )
    is TaoOpenGlRenderContext ->
      ComposeMapPresentationHost.linuxOpenGl(
        description = OPEN_GL_DESCRIPTION,
        gpuContext = renderContext.openGlGpuContext(),
        runOnGpuThread = runOnGpuThread,
      )
    // Tao has a private intermediate type; public callers receive the two leaves above.
    else -> error("Unsupported Tao GPU context: ${renderContext::class.simpleName}")
  }
}

@OptIn(ExperimentalMaplibreComposeApi::class)
private fun TaoMetalRenderContext.metalGpuContext(): () -> MetalComposeGpuContext = {
  MetalComposeGpuContext(skiaContext, NativeHandle(metalDevicePtr))
}

@OptIn(ExperimentalMaplibreComposeApi::class)
private fun TaoOpenGlRenderContext.openGlGpuContext(): () -> OpenGlComposeGpuContext = {
  OpenGlComposeGpuContext(
    skiaContext = skiaContext,
    // Tao restores the previous context and invalidates Skia's GL cache after the action.
    withContextCurrent = { action ->
      checkNotNull(withContextCurrent { action.run() }) {
        "$OPEN_GL_DESCRIPTION could not make the GL context current"
      }
    },
  )
}

private const val OPEN_GL_DESCRIPTION = "the Nucleus Tao OpenGL host"

private val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

/**
 * The [ComposeMapPresentationHost] for the current Nucleus Tao surface, or null until it has a GPU
 * context.
 *
 * Tao replaces the render context when it rebuilds a surface's graphics stack. Keying this host on
 * that context recreates the map bridge without retaining stale native handles.
 */
@Composable
public fun rememberTaoComposeMapPresentationHost(): ComposeMapPresentationHost? {
  val renderContext = rememberTaoGpuRenderContext() ?: return null
  return remember(renderContext) { taoComposeMapPresentationHost(renderContext) }
}
