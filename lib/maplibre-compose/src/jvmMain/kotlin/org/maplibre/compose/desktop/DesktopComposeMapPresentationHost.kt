@file:OptIn(org.maplibre.compose.util.ExperimentalMaplibreComposeApi::class)

package org.maplibre.compose.desktop

import org.maplibre.compose.desktop.bridge.AngleGl
import org.maplibre.compose.desktop.bridge.Direct3D12MapHost
import org.maplibre.compose.desktop.bridge.LinuxOpenGlMapHost
import org.maplibre.compose.desktop.bridge.MetalMapHost
import org.maplibre.compose.desktop.bridge.WindowsAngleMapHost
import org.maplibre.compose.desktop.bridge.ensureCapabilities
import org.maplibre.compose.desktop.bridge.isLinuxDesktop
import org.maplibre.compose.desktop.bridge.isWindowsDesktop
import org.maplibre.compose.location.XdgPortalWindow
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiHostException
import org.maplibre.compose.mlnffi.MlnFfiMapHost
import org.maplibre.compose.mlnffi.MlnFfiMapHostFactory
import org.maplibre.compose.mlnffi.MlnFfiMapHostResult
import org.maplibre.compose.mlnffi.RenderBackendPair

/** Window-scoped access; each [create] allocates a separate map-scoped bridge. */
internal abstract class DesktopComposeMapPresentationHost<C : ComposeGpuContext>(
  final override val description: String,
  private val gpuContext: () -> C?,
  private val gpuAccess: (Runnable) -> Unit,
  private val portalWindow: () -> XdgPortalWindow?,
  final override val bridges: List<RenderBackendPair>,
) : ComposeMapPresentationHost, MlnFfiMapHostFactory {
  final override val xdgPortalWindow: XdgPortalWindow?
    get() = portalWindow()

  fun runOnGpuThread(action: Runnable) = gpuAccess(action)

  fun <T> onGpuThread(action: () -> T): T {
    var result: Result<T>? = null
    runOnGpuThread { result = runCatching(action) }
    return checkNotNull(result) { "$description did not run the action it was given" }.getOrThrow()
  }

  /** Reads at each use, under GPU access, so first-frame initialization and replacement work. */
  fun currentContext(): C? = onGpuThread { gpuContext() }

  open fun <T> withContext(action: (C) -> T): T? = onGpuThread {
    val context = gpuContext() ?: return@onGpuThread null
    action(context)
  }

  final override fun create(backends: RenderBackendPair): MlnFfiMapHostResult =
    try {
      if (backends !in bridges) {
        return MlnFfiMapHostResult.Failed("$description cannot bridge $backends")
      }
      MlnFfiMapHostResult.Created(createMapHost(backends.producer))
    } catch (error: Throwable) {
      if (error is VirtualMachineError) throw error
      MlnFfiMapHostResult.Failed(
        "$description failed to bridge ${backends.producer} into Compose",
        error,
      )
    }

  protected abstract fun createMapHost(producer: MapRenderBackend): MlnFfiMapHost
}

internal class MetalPresentationHost(
  description: String,
  gpuContext: () -> MetalComposeGpuContext?,
  runOnGpuThread: (Runnable) -> Unit,
  xdgPortalWindow: () -> XdgPortalWindow? = { null },
) :
  DesktopComposeMapPresentationHost<MetalComposeGpuContext>(
    description,
    gpuContext,
    runOnGpuThread,
    xdgPortalWindow,
    listOf(MapRenderBackend.Metal, MapRenderBackend.Vulkan, MapRenderBackend.OpenGl).map {
      RenderBackendPair(it, ComposeRenderBackend.Metal)
    },
  ) {
  override fun createMapHost(producer: MapRenderBackend): MlnFfiMapHost =
    MetalMapHost(this, producer)
}

internal class Direct3D12PresentationHost(
  description: String,
  gpuContext: () -> Direct3D12ComposeGpuContext?,
  runOnGpuThread: (Runnable) -> Unit,
  xdgPortalWindow: () -> XdgPortalWindow? = { null },
) :
  DesktopComposeMapPresentationHost<Direct3D12ComposeGpuContext>(
    description,
    gpuContext,
    runOnGpuThread,
    xdgPortalWindow,
    listOf(MapRenderBackend.Vulkan, MapRenderBackend.OpenGl).map {
      RenderBackendPair(it, ComposeRenderBackend.Direct3D12)
    },
  ) {
  override fun createMapHost(producer: MapRenderBackend): MlnFfiMapHost =
    Direct3D12MapHost(this, producer)
}

internal class OpenGlPresentationHost(
  description: String,
  gpuContext: () -> OpenGlComposeGpuContext?,
  runOnGpuThread: (Runnable) -> Unit,
  xdgPortalWindow: () -> XdgPortalWindow? = { null },
) :
  DesktopComposeMapPresentationHost<OpenGlComposeGpuContext>(
    description,
    gpuContext,
    runOnGpuThread,
    xdgPortalWindow,
    listOf(MapRenderBackend.Vulkan, MapRenderBackend.OpenGl).map {
      RenderBackendPair(it, ComposeRenderBackend.OpenGl)
    },
  ) {
  override fun createMapHost(producer: MapRenderBackend): MlnFfiMapHost =
    LinuxOpenGlMapHost(this, producer)

  override fun <T> withContext(action: (OpenGlComposeGpuContext) -> T): T? =
    super.withContext { context ->
      context.withCurrent(description) {
        ensureCapabilities()
        action(context)
      }
    }
}

internal class AngleD3D11PresentationHost(
  description: String,
  gpuContext: () -> OpenGlComposeGpuContext?,
  runOnGpuThread: (Runnable) -> Unit,
  xdgPortalWindow: () -> XdgPortalWindow? = { null },
) :
  DesktopComposeMapPresentationHost<OpenGlComposeGpuContext>(
    description,
    gpuContext,
    runOnGpuThread,
    xdgPortalWindow,
    listOf(MapRenderBackend.Vulkan, MapRenderBackend.OpenGl).map {
      RenderBackendPair(it, ComposeRenderBackend.OpenGl)
    },
  ) {
  override fun createMapHost(producer: MapRenderBackend): MlnFfiMapHost =
    WindowsAngleMapHost(this, producer)

  override fun <T> withContext(action: (OpenGlComposeGpuContext) -> T): T? =
    super.withContext { context ->
      context.withCurrent(description) {
        check(AngleGl.isUsable()) { "Compose's ANGLE context has no usable GLES entry points" }
        action(context)
      }
    }
}

private fun <T> OpenGlComposeGpuContext.withCurrent(description: String, action: () -> T): T {
  var result: Result<T>? = null
  withContextCurrent { result = runCatching(action) }
  return checkNotNull(result) { "$description did not run the action it was given" }.getOrThrow()
}

internal fun checkNativeOpenGlPlatform(linux: Boolean = isLinuxDesktop()) {
  if (!linux) throw MlnFfiHostException("Native OpenGL interop requires Linux")
}

internal fun checkAnglePlatform(windows: Boolean = isWindowsDesktop()) {
  if (!windows) throw MlnFfiHostException("AngleD3D11 OpenGL interop requires Windows")
}
