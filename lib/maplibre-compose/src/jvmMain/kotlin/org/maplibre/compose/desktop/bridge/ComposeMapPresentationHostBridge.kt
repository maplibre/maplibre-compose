package org.maplibre.compose.desktop.bridge

import org.maplibre.compose.desktop.ComposeGpuContext
import org.maplibre.compose.desktop.ComposeMapPresentationHost
import org.maplibre.compose.desktop.OpenGlComposeGpuContext
import org.maplibre.compose.desktop.OpenGlInterop
import org.maplibre.compose.desktop.onGpuThread
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiHostException
import org.maplibre.compose.mlnffi.MlnFfiMapHostFactory
import org.maplibre.compose.mlnffi.MlnFfiMapHostResult
import org.maplibre.compose.mlnffi.RenderBackendPair

/** Builds the bridge from MapLibre Native into whatever [presentationHost] draws with. */
internal class ComposeMapPresentationHostFactory(
  private val presentationHost: ComposeMapPresentationHost
) : MlnFfiMapHostFactory {

  override val description: String
    get() = presentationHost.description

  override val bridges: List<RenderBackendPair> =
    when (presentationHost.backend) {
      ComposeRenderBackend.METAL ->
        listOf(RenderBackendPair(MapRenderBackend.METAL, ComposeRenderBackend.METAL))
      ComposeRenderBackend.OPENGL ->
        listOf(RenderBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL))
      ComposeRenderBackend.DIRECT3D12 ->
        listOf(RenderBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.DIRECT3D12))
    }

  override fun create(backends: RenderBackendPair): MlnFfiMapHostResult =
    try {
      val host =
        when (backends) {
          RenderBackendPair(MapRenderBackend.METAL, ComposeRenderBackend.METAL) ->
            MetalMapHost(presentationHost)
          RenderBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL) ->
            when (selectOpenGlBridge(presentationHost.openGlInterop)) {
              OpenGlBridge.NATIVE -> VulkanOpenGlMapHost(presentationHost)
              OpenGlBridge.ANGLE_D3D11 -> VulkanOpenGlWin32MapHost(presentationHost)
            }
          RenderBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.DIRECT3D12) ->
            VulkanDirect3D12MapHost(presentationHost)
          else -> return MlnFfiMapHostResult.Failed("$description cannot bridge $backends")
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

internal enum class OpenGlBridge {
  NATIVE,
  ANGLE_D3D11,
}

/** Selects the OpenGL bridge from the host capability instead of inferring it from the OS. */
internal fun selectOpenGlBridge(
  interop: OpenGlInterop,
  windows: Boolean = isWindowsDesktop(),
  linux: Boolean = isLinuxDesktop(),
): OpenGlBridge =
  when (interop) {
    OpenGlInterop.NATIVE -> {
      if (!linux) throw MlnFfiHostException("NATIVE OpenGL interop requires Linux")
      OpenGlBridge.NATIVE
    }
    OpenGlInterop.ANGLE_D3D11 -> {
      if (!windows) {
        throw MlnFfiHostException("ANGLE_D3D11 OpenGL interop requires Windows")
      }
      OpenGlBridge.ANGLE_D3D11
    }
  }

/**
 * This host's context right now, or null when it has none yet.
 *
 * Read at each use rather than captured, because a host does not necessarily have one to give when
 * its map is built: Compose backends commonly create their Skia context while producing the first
 * frame. Null means the caller skips this frame.
 */
internal fun ComposeMapPresentationHost.currentContext(): ComposeGpuContext? = onGpuThread {
  gpuContext()
}

/** This host's context as [T], or a failure naming what it reported instead. */
internal inline fun <reified T : ComposeGpuContext> ComposeMapPresentationHost.requireContext(): T {
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
internal fun <T> ComposeMapPresentationHost.withOpenGlContext(
  action: (OpenGlComposeGpuContext) -> T
): T =
  withOpenGlContextOrNull(action)
    ?: throw MlnFfiHostException("$description reports no GPU context")

/** [withOpenGlContext], but null means this host has no context for the current frame yet. */
internal fun <T> ComposeMapPresentationHost.withOpenGlContextOrNull(
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
      when (openGlInterop) {
        OpenGlInterop.NATIVE -> ensureCapabilities()
        OpenGlInterop.ANGLE_D3D11 ->
          check(AngleGl.isUsable()) { "Compose's ANGLE context has no usable GLES entry points" }
      }
      action(context)
    }
  }
  checkNotNull(result) { "$description did not run the action it was given" }.getOrThrow()
}
