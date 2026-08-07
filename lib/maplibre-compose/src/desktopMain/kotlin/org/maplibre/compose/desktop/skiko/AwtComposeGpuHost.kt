package org.maplibre.compose.desktop.skiko

import java.awt.Window
import org.jetbrains.skia.DirectContext
import org.maplibre.compose.desktop.ComposeGpuContext
import org.maplibre.compose.desktop.ComposeGpuHost
import org.maplibre.compose.desktop.Direct3D12ComposeGpuContext
import org.maplibre.compose.desktop.MetalComposeGpuContext
import org.maplibre.compose.desktop.OpenGlComposeGpuContext
import org.maplibre.compose.desktop.bridge.ObjectiveC
import org.maplibre.compose.desktop.skiko.SkikoReflection.getField
import org.maplibre.compose.desktop.skiko.SkikoReflection.invokeDeclaredNoArg
import org.maplibre.compose.desktop.skiko.SkikoReflection.staticInvoke
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.NativeHandle

/** Operating systems the AWT host distinguishes between. */
internal enum class HostOperatingSystem {
  LINUX,
  MACOS,
  WINDOWS,
  UNSUPPORTED;

  /** The backend Compose Desktop draws with here. */
  val composeBackend: ComposeRenderBackend?
    get() =
      when (this) {
        LINUX -> ComposeRenderBackend.OPENGL
        MACOS -> ComposeRenderBackend.METAL
        WINDOWS -> ComposeRenderBackend.DIRECT3D12
        UNSUPPORTED -> null
      }

  companion object {
    fun current(): HostOperatingSystem {
      val os = System.getProperty("os.name")?.lowercase().orEmpty()
      return when {
        os.contains("linux") -> LINUX
        os.contains("mac") -> MACOS
        os.contains("windows") -> WINDOWS
        else -> UNSUPPORTED
      }
    }
  }
}

/**
 * A [ComposeGpuHost] for one AWT-backed Compose Desktop [window].
 *
 * Compose Desktop exposes no supported hook for any of this, so it is read reflectively; all of
 * that is confined to [SkikoReflection] and to the supplied window. An application running its own
 * Compose windowing supplies a different host and needs no reflection at all.
 */
internal class AwtComposeGpuHost(private val window: Window) : ComposeGpuHost {

  private val operatingSystem = HostOperatingSystem.current()

  override val description: String
    get() = "an AWT Compose window on ${operatingSystem.name.lowercase()}"

  /**
   * Which backend Compose Desktop picks is decided by the operating system, so this is answerable
   * before Skiko has built anything — unlike [gpuContext].
   */
  override val backend: ComposeRenderBackend
    get() =
      checkNotNull(operatingSystem.composeBackend) {
        "MapLibre Compose has no desktop GPU bridge for ${System.getProperty("os.name")}."
      }

  /** Skiko's internals are only safe to touch on the AWT event thread, which also draws. */
  override fun runOnGpuThread(action: Runnable) {
    SkikoReflection.onEdt { action.run() }
  }

  /**
   * Null until Compose Desktop has a window whose Skia context exists, which on Linux means until
   * it has drawn a frame. A redrawer for a backend other than [backend] is a different matter, and
   * throws.
   */
  override fun gpuContext(): ComposeGpuContext? {
    val layer = SkikoReflection.findSkiaLayer(window) ?: return null
    return when (operatingSystem) {
      HostOperatingSystem.MACOS -> metalContext(layer)
      HostOperatingSystem.LINUX -> openGlContext(layer)
      HostOperatingSystem.WINDOWS -> direct3D12Context(layer)
      HostOperatingSystem.UNSUPPORTED -> null
    }
  }

  private fun metalContext(layer: Any): MetalComposeGpuContext? {
    val handler = SkikoReflection.requireMetalContextHandler(layer)
    val skiaContext = handler.directContext() ?: return null
    val device = SkikoReflection.findMetalDevice(handler) ?: return null
    // Skiko's device object is its own wrapper; `adapter` holds the real `id<MTLDevice>`, which is
    // what MapLibre's texture has to be allocated on.
    val adapter =
      ObjectiveC.sendPointer(device.ptr, SkikoReflection.SKIKO_METAL_DEVICE_ADAPTER).takeIf {
        it != 0L
      } ?: return null
    return MetalComposeGpuContext(skiaContext = skiaContext, device = NativeHandle(adapter))
  }

  private fun direct3D12Context(layer: Any): Direct3D12ComposeGpuContext? {
    val redrawer = SkikoReflection.requireRedrawer(layer, SkikoReflection.DIRECT3D_REDRAWER_CLASS)
    val handler =
      SkikoReflection.requireContextHandler(redrawer, SkikoReflection.DIRECT3D_REDRAWER_CLASS)
    val skiaContext = handler.directContext(makeContext = "makeContext") ?: return null
    val device = SkikoReflection.findDirect3DDevice(redrawer) ?: return null
    val rawDevice = SkikoDirect3DDeviceLayout.rawDevice(device).takeIf { it != 0L } ?: return null
    return Direct3D12ComposeGpuContext(skiaContext = skiaContext, device = NativeHandle(rawDevice))
  }

  private fun openGlContext(layer: Any): OpenGlComposeGpuContext? {
    val redrawer =
      SkikoReflection.requireRedrawer(layer, SkikoReflection.LINUX_OPENGL_REDRAWER_CLASS)
    val handler =
      SkikoReflection.requireContextHandler(redrawer, SkikoReflection.LINUX_OPENGL_REDRAWER_CLASS)
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
        ?: error("${SkikoReflection.SKIA_LAYER_CLASS}.backedLayer was null")
    val context =
      redrawer.getField("context") as? Long
        ?: error("${SkikoReflection.LINUX_OPENGL_REDRAWER_CLASS}.context was null")
    check(context != 0L) { "${SkikoReflection.LINUX_OPENGL_REDRAWER_CLASS}.context was zero" }

    val surfaceHelpers = Class.forName(SkikoReflection.AWT_LINUX_DRAWING_SURFACE_HELPERS_CLASS)
    val drawingSurface = surfaceHelpers.staticInvoke("lockLinuxDrawingSurface", backedLayer)
    try {
      Class.forName(SkikoReflection.LINUX_OPENGL_REDRAWER_HELPERS_CLASS)
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
