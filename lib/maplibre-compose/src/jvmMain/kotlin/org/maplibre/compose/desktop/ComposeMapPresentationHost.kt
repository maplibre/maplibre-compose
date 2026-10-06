package org.maplibre.compose.desktop

import org.maplibre.compose.location.XdgPortalWindow
import org.maplibre.compose.mlnffi.MlnFfiMapHostFactory

/**
 * Supplies the window integrations a map uses on desktop.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 *
 * Create a host with [metal], [direct3D12], [openGl], or [angleD3D11], then install it with
 * [ProvideMapPresentationHost]. The factory determines the supported MapLibre producer bridges
 * before a GPU context exists. Each map gets its own bridge and rendering resources.
 *
 * The context callback is read at every use, under the host's exclusive GPU access. It may return
 * null during initialization or replacement; the map skips frames until it is available. A host may
 * replace its device or Skia context within its integration; the map recreates resources tied to
 * it. Replace the host itself to change integrations.
 *
 * The GPU access callback must run its action synchronously, with exclusive access to the host's
 * Skia context on its owning thread, without overlapping Compose frame replay. It must run directly
 * when the caller already has exclusive access, and propagate exceptions. It must also support
 * cleanup while the context callback returns null.
 */
public sealed interface ComposeMapPresentationHost {
  /** A short description of this host, used in diagnostics. */
  public val description: String

  /** The window that XDG portals use to parent system dialogs, when the host can provide one. */
  public val xdgPortalWindow: XdgPortalWindow?

  public companion object {
    /**
     * Creates a macOS Metal host. Supports MapLibre Metal, Vulkan, and OpenGL producers, in that
     * preference order.
     *
     * @param gpuContext Supplies the current borrowed context under exclusive GPU access, or null.
     * @param runOnGpuThread Runs an action synchronously with exclusive GPU access, including
     *   cleanup when no context is available.
     * @param xdgPortalWindow Supplies the current platform window when needed for system dialogs.
     */
    public fun metal(
      description: String,
      gpuContext: () -> MetalComposeGpuContext?,
      runOnGpuThread: (Runnable) -> Unit,
      xdgPortalWindow: () -> XdgPortalWindow? = { null },
    ): ComposeMapPresentationHost =
      MetalPresentationHost(description, gpuContext, runOnGpuThread, xdgPortalWindow)

    /**
     * Creates a Windows Direct3D 12 host. Supports MapLibre Vulkan and OpenGL producers, in that
     * preference order. Callback contracts are described on [ComposeMapPresentationHost].
     */
    public fun direct3D12(
      description: String,
      gpuContext: () -> Direct3D12ComposeGpuContext?,
      runOnGpuThread: (Runnable) -> Unit,
      xdgPortalWindow: () -> XdgPortalWindow? = { null },
    ): ComposeMapPresentationHost =
      Direct3D12PresentationHost(description, gpuContext, runOnGpuThread, xdgPortalWindow)

    /**
     * Creates a Linux native OpenGL host. Supports MapLibre Vulkan and OpenGL producers, in that
     * preference order. Callback contracts are described on [ComposeMapPresentationHost].
     *
     * @throws IllegalStateException on platforms other than Linux.
     */
    public fun openGl(
      description: String,
      gpuContext: () -> OpenGlComposeGpuContext?,
      runOnGpuThread: (Runnable) -> Unit,
      xdgPortalWindow: () -> XdgPortalWindow? = { null },
    ): ComposeMapPresentationHost {
      checkNativeOpenGlPlatform()
      return OpenGlPresentationHost(description, gpuContext, runOnGpuThread, xdgPortalWindow)
    }

    /**
     * Creates a Windows ANGLE host with Direct3D 11 texture sharing. Supports MapLibre Vulkan and
     * OpenGL producers, in that preference order. Callback contracts are described on
     * [ComposeMapPresentationHost].
     *
     * @throws IllegalStateException on platforms other than Windows.
     */
    public fun angleD3D11(
      description: String,
      gpuContext: () -> OpenGlComposeGpuContext?,
      runOnGpuThread: (Runnable) -> Unit,
      xdgPortalWindow: () -> XdgPortalWindow? = { null },
    ): ComposeMapPresentationHost {
      checkAnglePlatform()
      return AngleD3D11PresentationHost(description, gpuContext, runOnGpuThread, xdgPortalWindow)
    }
  }
}

internal val ComposeMapPresentationHost.mapHostFactory: MlnFfiMapHostFactory
  get() = this as DesktopComposeMapPresentationHost<*>
