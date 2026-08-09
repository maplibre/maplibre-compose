package org.maplibre.compose.desktop.bridge

import org.maplibre.compose.desktop.ComposeGpuContext
import org.maplibre.compose.desktop.ComposeGpuHost
import org.maplibre.compose.desktop.OpenGlComposeGpuContext
import org.maplibre.compose.desktop.onGpuThread
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiHostException
import org.maplibre.compose.mlnffi.MlnFfiMapHostFactory
import org.maplibre.compose.mlnffi.MlnFfiMapHostResult
import org.maplibre.compose.mlnffi.RenderBackendPair

/** Builds the bridge from MapLibre Native into whatever [gpuHost] draws with. */
internal class ComposeGpuMapHostFactory(private val gpuHost: ComposeGpuHost) :
  MlnFfiMapHostFactory {

  override val description: String
    get() = gpuHost.description

  override val backends: RenderBackendPair =
    when (gpuHost.backend) {
      ComposeRenderBackend.METAL ->
        RenderBackendPair(MapRenderBackend.METAL, ComposeRenderBackend.METAL)
      ComposeRenderBackend.OPENGL ->
        RenderBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL)
      ComposeRenderBackend.DIRECT3D12 ->
        RenderBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.DIRECT3D12)
    }

  override fun create(): MlnFfiMapHostResult =
    try {
      val host =
        when (gpuHost.backend) {
          ComposeRenderBackend.METAL -> MetalMapHost(gpuHost)
          ComposeRenderBackend.OPENGL -> VulkanOpenGlMapHost(gpuHost)
          ComposeRenderBackend.DIRECT3D12 -> VulkanDirect3D12MapHost(gpuHost)
        }
      MlnFfiMapHostResult.Created(host)
    } catch (error: Throwable) {
      if (error is VirtualMachineError) throw error
      MlnFfiMapHostResult.Failed(
        "$description failed to bridge ${backends.producer} into Compose",
        error,
      )
    }
}

/**
 * This host's context right now, or null when it has none yet.
 *
 * Read at each use rather than captured, because a host does not necessarily have one to give when
 * its map is built: Compose backends commonly create their Skia context while producing the first
 * frame. Null means the caller skips this frame.
 */
internal fun ComposeGpuHost.currentContext(): ComposeGpuContext? = onGpuThread { gpuContext() }

/** This host's context as [T], or a failure naming what it reported instead. */
internal inline fun <reified T : ComposeGpuContext> ComposeGpuHost.requireContext(): T {
  val context = currentContext() ?: throw MlnFfiHostException("$description reports no GPU context")
  return context as? T
    ?: throw MlnFfiHostException(
      "$description switched from ${T::class.simpleName} to ${context::class.simpleName}"
    )
}

/**
 * Runs [action] on the GPU thread with Compose's OpenGL context current, which is what every GL
 * call touching the shared texture needs.
 *
 * Scoped on both axes because Compose Desktop's is: making Skiko's context current locks the
 * window's drawing surface, and the surface has to stay locked until the context is released again.
 */
internal fun <T> ComposeGpuHost.withOpenGlContext(action: (OpenGlComposeGpuContext) -> T): T =
  withOpenGlContextOrNull(action)
    ?: throw MlnFfiHostException("$description reports no GPU context")

/** [withOpenGlContext], but null means this host has no context for the current frame yet. */
internal fun <T> ComposeGpuHost.withOpenGlContextOrNull(
  action: (OpenGlComposeGpuContext) -> T
): T? = onGpuThread {
  val reported = gpuContext() ?: return@onGpuThread null
  val context =
    reported as? OpenGlComposeGpuContext
      ?: throw MlnFfiHostException(
        "$description switched from OpenGlComposeGpuContext to ${reported::class.simpleName}"
      )
  var result: Result<T>? = null
  context.withContextCurrent {
    result = runCatching {
      ensureCapabilities()
      action(context)
    }
  }
  checkNotNull(result) { "$description did not run the action it was given" }.getOrThrow()
}
