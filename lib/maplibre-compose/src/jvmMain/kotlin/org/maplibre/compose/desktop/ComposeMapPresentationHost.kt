package org.maplibre.compose.desktop

import org.maplibre.compose.desktop.skiko.HostOperatingSystem
import org.maplibre.compose.location.XdgPortalWindow
import org.maplibre.compose.mlnffi.MlnFfiMapHostFactory
import org.maplibre.compose.util.ExperimentalMaplibreComposeApi

/**
 * Gives desktop maps access to the GPU context that a Compose window draws with, so that maps draw
 * into that window.
 *
 * Install a host with [ProvideMapPresentationHost]. For an AWT-backed Compose window, such as one
 * from `singleWindowApplication`, use [rememberAwtComposeMapPresentationHost]. A window that draws
 * Compose another way creates its host with the factory for its operating system and graphics API:
 * [macosMetal], [windowsDirect3d12], [linuxOpenGl], or [windowsAngle]. These factories take Skia
 * types from Skiko, which may change in any minor release, so they require opt-in to
 * [ExperimentalMaplibreComposeApi].
 *
 * A host belongs to one window. Each map in the window gets its own GPU resources. To change the
 * window or graphics API, create a new host and install it instead.
 */
public sealed interface ComposeMapPresentationHost {
  /** A short description of this host, shown in logs and error messages. */
  public val description: String

  public companion object {
    /**
     * Creates a host for a window that draws Compose with Metal on macOS.
     *
     * Works with the Metal, Vulkan, and OpenGL MapLibre runtimes.
     *
     * @param description A short description of the window, shown in logs and error messages.
     * @param gpuContext Returns the window's current Metal context. It is called only inside
     *   [runOnGpuThread]. Return null while the window has no context, such as before its first
     *   frame; maps skip frames until it returns one. Return a new context after the window
     *   replaces its device or Skia context; maps then recreate their GPU resources.
     * @param runOnGpuThread Runs the given action with exclusive access to the window's Skia
     *   context, on the thread that owns that context, while Compose isn't drawing with it. It may
     *   be called from any thread. It must run the action before it returns, run the action
     *   directly when called from inside an action it is running, and rethrow anything the action
     *   throws. It must run actions even when [gpuContext] returns null, because maps release their
     *   resources this way.
     * @throws IllegalStateException if the current operating system isn't macOS.
     */
    @ExperimentalMaplibreComposeApi
    public fun macosMetal(
      description: String,
      gpuContext: () -> MetalComposeGpuContext?,
      runOnGpuThread: (Runnable) -> Unit,
    ): ComposeMapPresentationHost {
      checkOperatingSystem("macosMetal", HostOperatingSystem.Macos)
      return MetalPresentationHost(description, gpuContext, runOnGpuThread)
    }

    /**
     * Creates a host for a window that draws Compose with Direct3D 12 on Windows.
     *
     * Works with the Vulkan and OpenGL MapLibre runtimes.
     *
     * @param description A short description of the window, shown in logs and error messages.
     * @param gpuContext Returns the window's current Direct3D 12 context. It is called only inside
     *   [runOnGpuThread]. Return null while the window has no context, such as before its first
     *   frame; maps skip frames until it returns one. Return a new context after the window
     *   replaces its device or Skia context; maps then recreate their GPU resources.
     * @param runOnGpuThread Runs the given action with exclusive access to the window's Skia
     *   context, on the thread that owns that context, while Compose isn't drawing with it. It may
     *   be called from any thread. It must run the action before it returns, run the action
     *   directly when called from inside an action it is running, and rethrow anything the action
     *   throws. It must run actions even when [gpuContext] returns null, because maps release their
     *   resources this way.
     * @throws IllegalStateException if the current operating system isn't Windows.
     */
    @ExperimentalMaplibreComposeApi
    public fun windowsDirect3d12(
      description: String,
      gpuContext: () -> Direct3D12ComposeGpuContext?,
      runOnGpuThread: (Runnable) -> Unit,
    ): ComposeMapPresentationHost {
      checkOperatingSystem("windowsDirect3d12", HostOperatingSystem.Windows)
      return Direct3D12PresentationHost(description, gpuContext, runOnGpuThread)
    }

    /**
     * Creates a host for a window that draws Compose with OpenGL on Linux.
     *
     * Works with the Vulkan and OpenGL MapLibre runtimes.
     *
     * @param description A short description of the window, shown in logs and error messages.
     * @param gpuContext Returns the window's current OpenGL context. It is called only inside
     *   [runOnGpuThread]. Return null while the window has no context, such as before its first
     *   frame; maps skip frames until it returns one. Return a new context after the window
     *   replaces its Skia context; maps then recreate their GPU resources.
     * @param runOnGpuThread Runs the given action with exclusive access to the window's Skia
     *   context, on the thread that owns that context, while Compose isn't drawing with it. It may
     *   be called from any thread. It must run the action before it returns, run the action
     *   directly when called from inside an action it is running, and rethrow anything the action
     *   throws. It must run actions even when [gpuContext] returns null, because maps release their
     *   resources this way.
     * @param xdgPortalWindow Returns the window that XDG portals use as the parent of system
     *   dialogs, such as the location permission prompt. It is called on the composition thread
     *   each time [ProvideMapPresentationHost] composes. Return null when the window has no X11 or
     *   Wayland handle; portals then show their dialogs without a parent.
     * @throws IllegalStateException if the current operating system isn't Linux.
     */
    @ExperimentalMaplibreComposeApi
    public fun linuxOpenGl(
      description: String,
      gpuContext: () -> OpenGlComposeGpuContext?,
      runOnGpuThread: (Runnable) -> Unit,
      xdgPortalWindow: () -> XdgPortalWindow? = { null },
    ): ComposeMapPresentationHost {
      checkOperatingSystem("linuxOpenGl", HostOperatingSystem.Linux)
      return OpenGlPresentationHost(description, gpuContext, runOnGpuThread, xdgPortalWindow)
    }

    /**
     * Creates a host for a window that draws Compose with OpenGL ES through ANGLE on Windows.
     *
     * ANGLE must use its Direct3D 11 backend, which is its default on Windows. Works with the
     * Vulkan and OpenGL MapLibre runtimes.
     *
     * @param description A short description of the window, shown in logs and error messages.
     * @param gpuContext Returns the window's current ANGLE context. It is called only inside
     *   [runOnGpuThread]. Return null while the window has no context, such as before its first
     *   frame; maps skip frames until it returns one. Return a new context after the window
     *   replaces its Skia context; maps then recreate their GPU resources.
     * @param runOnGpuThread Runs the given action with exclusive access to the window's Skia
     *   context, on the thread that owns that context, while Compose isn't drawing with it. It may
     *   be called from any thread. It must run the action before it returns, run the action
     *   directly when called from inside an action it is running, and rethrow anything the action
     *   throws. It must run actions even when [gpuContext] returns null, because maps release their
     *   resources this way.
     * @throws IllegalStateException if the current operating system isn't Windows.
     */
    @ExperimentalMaplibreComposeApi
    public fun windowsAngle(
      description: String,
      gpuContext: () -> OpenGlComposeGpuContext?,
      runOnGpuThread: (Runnable) -> Unit,
    ): ComposeMapPresentationHost {
      checkOperatingSystem("windowsAngle", HostOperatingSystem.Windows)
      return AngleD3D11PresentationHost(description, gpuContext, runOnGpuThread)
    }
  }
}

internal val ComposeMapPresentationHost.mapHostFactory: MlnFfiMapHostFactory
  get() = this as DesktopComposeMapPresentationHost<*>

/** The window that XDG portals use as the parent of system dialogs, or null. */
internal val ComposeMapPresentationHost.xdgPortalWindow: XdgPortalWindow?
  get() = (this as DesktopComposeMapPresentationHost<*>).xdgPortalWindow
