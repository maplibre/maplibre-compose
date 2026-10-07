@file:OptIn(org.maplibre.compose.util.ExperimentalMaplibreComposeApi::class)

package org.maplibre.compose.desktop.skiko

import java.awt.Window
import org.jetbrains.skia.DirectContext
import org.maplibre.compose.desktop.ComposeMapPresentationHost
import org.maplibre.compose.desktop.Direct3D12ComposeGpuContext
import org.maplibre.compose.desktop.MetalComposeGpuContext
import org.maplibre.compose.desktop.OpenGlComposeGpuContext
import org.maplibre.compose.desktop.bridge.ObjectiveC
import org.maplibre.compose.desktop.skiko.SkikoReflection.getField
import org.maplibre.compose.desktop.skiko.SkikoReflection.invokeDeclaredNoArg
import org.maplibre.compose.desktop.skiko.SkikoReflection.staticInvoke
import org.maplibre.compose.location.XdgPortalWindow
import org.maplibre.compose.mlnffi.NativeHandle

/** Operating systems the AWT host distinguishes between. */
internal enum class HostOperatingSystem {
  Linux,
  Macos,
  Windows,
  Unsupported;

  companion object {
    fun current(): HostOperatingSystem {
      val os = System.getProperty("os.name")?.lowercase().orEmpty()
      return when {
        os.contains("linux") -> Linux
        os.contains("mac") -> Macos
        os.contains("windows") -> Windows
        else -> Unsupported
      }
    }
  }
}

/**
 * A [ComposeMapPresentationHost] for one AWT-backed Compose Desktop [window].
 *
 * Compose Desktop exposes no supported hook for any of this, so it is read reflectively; all of
 * that is confined to [SkikoReflection] and to the supplied window. An application running its own
 * Compose windowing supplies a different host and needs no reflection at all.
 */
internal class AwtComposeMapPresentationHost(private val window: Window) {

  private val operatingSystem = HostOperatingSystem.current()

  private val description: String
    get() = "an AWT Compose window on ${operatingSystem.name.lowercase()}"

  private val xdgPortalWindow: XdgPortalWindow?
    get() {
      if (operatingSystem != HostOperatingSystem.Linux) return null
      val windowId = SkikoReflection.findNativeWindowHandle(window) ?: return null
      return XdgPortalWindow.X11(windowId)
    }

  val presentationHost: ComposeMapPresentationHost =
    when (operatingSystem) {
      HostOperatingSystem.Macos ->
        ComposeMapPresentationHost.metal(
          "$description using Metal",
          { SkikoReflection.findSkiaLayer(window)?.let(::metalContext) },
          ::runOnGpuThread,
          { xdgPortalWindow },
        )
      HostOperatingSystem.Windows ->
        ComposeMapPresentationHost.direct3D12(
          "$description using Direct3D 12",
          { SkikoReflection.findSkiaLayer(window)?.let(::direct3D12Context) },
          ::runOnGpuThread,
          { xdgPortalWindow },
        )
      HostOperatingSystem.Linux ->
        ComposeMapPresentationHost.openGl(
          "$description using OpenGL",
          { SkikoReflection.findSkiaLayer(window)?.let(::openGlContext) },
          ::runOnGpuThread,
          { xdgPortalWindow },
        )
      HostOperatingSystem.Unsupported ->
        error("MapLibre Compose has no desktop GPU bridge for ${System.getProperty("os.name")}.")
    }

  /**
   * Skiko updates pictures on the AWT event thread, then Metal and Direct3D replay them on a render
   * worker. Their render lock keeps shared textures and the Skia context out of both threads at
   * once. Linux replays on the event thread itself.
   */
  private fun runOnGpuThread(action: Runnable) {
    SkikoReflection.onEdt {
      val layer = SkikoReflection.findSkiaLayer(window)
      val renderLock =
        when (operatingSystem) {
          HostOperatingSystem.Macos ->
            layer?.let {
              SkikoReflection.requireRenderLock(it, SkikoReflection.MetalRedrawerClass)
            }
          HostOperatingSystem.Windows ->
            layer?.let {
              SkikoReflection.requireRenderLock(it, SkikoReflection.Direct3dRedrawerClass)
            }
          HostOperatingSystem.Linux,
          HostOperatingSystem.Unsupported -> null
        }
      if (renderLock == null) action.run() else synchronized(renderLock) { action.run() }
    }
  }

  private fun metalContext(layer: Any): MetalComposeGpuContext? {
    val handler = SkikoReflection.requireMetalContextHandler(layer)
    val skiaContext = handler.directContext() ?: return null
    val device = SkikoReflection.findMetalDevice(handler) ?: return null
    // Skiko's device object is its own wrapper; `adapter` holds the real `id<MTLDevice>`, which is
    // what MapLibre's texture has to be allocated on.
    val adapter =
      ObjectiveC.sendPointer(device.ptr, SkikoReflection.SkikoMetalDeviceAdapter).takeIf {
        it != 0L
      } ?: return null
    return MetalComposeGpuContext(skiaContext = skiaContext, device = NativeHandle(adapter))
  }

  private fun direct3D12Context(layer: Any): Direct3D12ComposeGpuContext? {
    val redrawer = SkikoReflection.requireRedrawer(layer, SkikoReflection.Direct3dRedrawerClass)
    val handler =
      SkikoReflection.requireContextHandler(redrawer, SkikoReflection.Direct3dRedrawerClass)
    val skiaContext = handler.directContext(makeContext = "makeContext") ?: return null
    val device = SkikoReflection.findDirect3DDevice(redrawer) ?: return null
    val rawDevice = SkikoDirect3DDeviceLayout.rawDevice(device).takeIf { it != 0L } ?: return null
    return Direct3D12ComposeGpuContext(skiaContext = skiaContext, device = NativeHandle(rawDevice))
  }

  private fun openGlContext(layer: Any): OpenGlComposeGpuContext? {
    val redrawer = SkikoReflection.requireRedrawer(layer, SkikoReflection.LinuxOpenGlRedrawerClass)
    val handler =
      SkikoReflection.requireContextHandler(redrawer, SkikoReflection.LinuxOpenGlRedrawerClass)
    val skiaContext = handler.directContext() ?: return null
    return OpenGlComposeGpuContext(
      skiaContext = skiaContext,
      withContextCurrent = { action -> withOpenGlContextCurrent(layer, redrawer, action) },
    )
  }

  /**
   * Makes Compose's GL context current for [action]. Skiko's context lives on the redrawer and is
   * made current against a drawing surface that has to stay locked for as long as it is.
   */
  private fun withOpenGlContextCurrent(layer: Any, redrawer: Any, action: Runnable) {
    val backedLayer =
      layer.getField("backedLayer")
        ?: error("${SkikoReflection.SkiaLayerClass}.backedLayer was null")
    val context =
      redrawer.getField("context") as? Long
        ?: error("${SkikoReflection.LinuxOpenGlRedrawerClass}.context was null")
    check(context != 0L) { "${SkikoReflection.LinuxOpenGlRedrawerClass}.context was zero" }

    val surfaceHelpers = Class.forName(SkikoReflection.AwtLinuxDrawingSurfaceHelpersClass)
    val drawingSurface = surfaceHelpers.staticInvoke("lockLinuxDrawingSurface", backedLayer)
    try {
      Class.forName(SkikoReflection.LinuxOpenGlRedrawerHelpersClass)
        .staticInvoke("access\$makeCurrent", drawingSurface, context)
      action.run()
    } finally {
      surfaceHelpers.staticInvoke("unlockLinuxDrawingSurface", drawingSurface)
    }
  }

  /**
   * Skiko's context handlers create their [DirectContext] lazily, on the first frame. Null here
   * means "not yet", which the map reports as a skipped frame rather than a failure.
   */
  private fun Any.directContext(makeContext: String = "getContext"): DirectContext? =
    (getField("context") as? DirectContext)
      ?: run {
        invokeDeclaredNoArg("initContext")
        (getField("context") as? DirectContext)
          ?: invokeDeclaredNoArg(makeContext) as? DirectContext
      }
}
