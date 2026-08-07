package org.maplibre.compose.desktop.skiko

import java.awt.Component
import java.awt.Container
import java.awt.Window
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.reflect.Field
import java.lang.reflect.Method
import javax.swing.SwingUtilities
import org.maplibre.compose.mlnffi.MlnFfiHostException

/**
 * Reaches into Compose Desktop's Skiko internals for the GPU objects it does not expose.
 *
 * Every reflective access in MapLibre Compose lives in this file.
 */
internal object SkikoReflection {
  const val SKIA_LAYER_CLASS = "org.jetbrains.skiko.SkiaLayer"
  const val COMPOSE_WINDOW_CLASS = "androidx.compose.ui.awt.ComposeWindow"
  const val METAL_REDRAWER_CLASS = "org.jetbrains.skiko.redrawer.MetalRedrawer"
  const val DIRECT3D_REDRAWER_CLASS = "org.jetbrains.skiko.redrawer.Direct3DRedrawer"
  const val LINUX_OPENGL_REDRAWER_CLASS = "org.jetbrains.skiko.redrawer.LinuxOpenGLRedrawer"
  const val LINUX_OPENGL_REDRAWER_HELPERS_CLASS =
    "org.jetbrains.skiko.redrawer.LinuxOpenGLRedrawerKt"
  const val AWT_LINUX_DRAWING_SURFACE_HELPERS_CLASS = "org.jetbrains.skiko.AWTLinuxDrawingSurfaceKt"
  const val DIRECT3D_CONTEXT_HANDLER_CLASS = "org.jetbrains.skiko.context.Direct3DContextHandler"
  const val METAL_CONTEXT_HANDLER_CLASS = "org.jetbrains.skiko.context.MetalContextHandler"
  const val CONTEXT_HANDLER_CLASS = "org.jetbrains.skiko.context.ContextHandler"

  /**
   * Skiko's Objective-C wrapper around the Metal device, and the selector on it that answers with
   * the `id<MTLDevice>` underneath. Neither is published API (private to Skiko's
   * `MetalRedrawer.mm`), so both are pinned by `SkikoMetalDeviceContractTest`.
   */
  const val SKIKO_METAL_DEVICE_CLASS: String = "MetalDevice"

  const val SKIKO_METAL_DEVICE_ADAPTER: String = "adapter"

  fun findSkiaLayer(): Any? = findSkiaLayerComponent() ?: findComposeWindowSkiaLayer()

  fun requireSkiaLayer(): Any =
    findSkiaLayer()
      ?: throw MlnFfiHostException("Could not find a live $SKIA_LAYER_CLASS. ${describeWindows()}")

  fun requireRedrawer(layer: Any, expectedClass: String): Any {
    val redrawer =
      layer.invokeNoArg("getRedrawer\$skiko")
        ?: throw MlnFfiHostException("$SKIA_LAYER_CLASS.getRedrawer\$skiko returned null")
    if (!Class.forName(expectedClass).isAssignableFrom(redrawer.javaClass)) {
      throw MlnFfiHostException(
        "Skiko redrawer was ${redrawer.javaClass.name}, expected $expectedClass. " +
          "Compose is probably rendering with a different backend than the map host assumed."
      )
    }
    return redrawer
  }

  fun requireContextHandler(redrawer: Any, redrawerClass: String): Any =
    redrawer.getField("contextHandler")
      ?: throw MlnFfiHostException("$redrawerClass.contextHandler was null")

  /**
   * The Direct3D device Compose renders with on Windows. Skiko keeps it on the redrawer, and only
   * after the first frame has initialized the swap chain — until then this is null.
   */
  fun findDirect3DDevice(redrawer: Any): SkikoDirect3DDevice? {
    val ptr = redrawer.getField("device") as? Long ?: return null
    if (ptr == 0L) return null
    return SkikoDirect3DDevice(ptr)
  }

  fun requireMetalContextHandler(layer: Any): Any =
    requireContextHandler(requireRedrawer(layer, METAL_REDRAWER_CLASS), METAL_REDRAWER_CLASS)

  /**
   * The Metal device Compose renders with on macOS; MapLibre must allocate its texture on the same
   * device Skia samples from. Skiko's `MetalDevice` value class inlines to a `long`, but the boxed
   * shape is handled too.
   *
   * Null before Skiko has created it, which the caller reports as a context that does not exist yet
   * rather than as a failure.
   */
  fun findMetalDevice(contextHandler: Any): SkikoMetalDevice? {
    val device = contextHandler.getField("device") ?: return null
    val ptr =
      when (device) {
        is Long -> device
        else ->
          device.getField("ptr") as? Long
            ?: device.invokeNoArg("getPtr") as? Long
            ?: throw MlnFfiHostException(
              "${device.javaClass.name} did not expose the Skiko MetalDevice pointer"
            )
      }
    if (ptr == 0L) return null
    return SkikoMetalDevice(ptr)
  }

  /** Runs [block] on the AWT event thread, where Skiko's internals are safe to touch. */
  fun <T> onEdt(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(block) }
    return result!!.getOrThrow()
  }

  fun describeWindows(): String =
    Window.getWindows().joinToString(prefix = "Windows: ", separator = " | ") { window ->
      buildString {
        append(window.javaClass.name)
        append("(displayable=${window.isDisplayable}, showing=${window.isShowing})")
        append(" children=[")
        append(window.walkComponents().drop(1).take(12).joinToString { it.javaClass.name })
        append("]")
      }
    }

  private fun findSkiaLayerComponent(): Any? =
    Window.getWindows()
      .asSequence()
      .filter { it.isDisplayable }
      .flatMap { it.walkComponents() }
      .firstOrNull { isSkiaLayer(it) }

  private fun findComposeWindowSkiaLayer(): Any? =
    Window.getWindows()
      .asSequence()
      .filter { it.isDisplayable && it.javaClass.name == COMPOSE_WINDOW_CLASS }
      .mapNotNull { window ->
        runCatching {
            val composePanel = window.getField("composePanel") ?: return@mapNotNull null
            val content =
              composePanel.invokeDeclaredNoArg("getContentComponent") ?: return@mapNotNull null
            if (isSkiaLayer(content)) content
            else (content as? Component)?.walkComponents()?.firstOrNull { isSkiaLayer(it) }
          }
          .getOrNull()
      }
      .firstOrNull()

  private fun isSkiaLayer(value: Any): Boolean = Class.forName(SKIA_LAYER_CLASS).isInstance(value)

  private fun Component.walkComponents(): Sequence<Component> = sequence {
    yield(this@walkComponents)
    if (this@walkComponents is Container) {
      for (child in components) yieldAll(child.walkComponents())
    }
  }

  fun Any.invokeNoArg(name: String): Any? = javaClass.getMethod(name).invoke(this)

  fun Any.invokeDeclaredNoArg(name: String): Any? =
    javaClass.findMethod(name).let {
      it.isAccessible = true
      it.invoke(this)
    }

  fun Any.getField(name: String): Any? =
    javaClass.findField(name).let {
      it.isAccessible = true
      it.get(this)
    }

  fun Class<*>.staticInvoke(name: String, vararg args: Any?): Any? =
    methods.firstOrNull { it.name == name && it.parameterCount == args.size }?.invoke(null, *args)
      ?: throw NoSuchMethodException("$name/${args.size} on ${this.name}")

  fun Class<*>.findField(name: String): Field {
    var current: Class<*>? = this
    while (current != null) {
      try {
        return current.getDeclaredField(name)
      } catch (_: NoSuchFieldException) {
        current = current.superclass
      }
    }
    throw NoSuchFieldException("${this.name}.$name")
  }

  fun Class<*>.findMethod(name: String, vararg parameterTypes: Class<*>): Method {
    var current: Class<*>? = this
    while (current != null) {
      try {
        return current.getDeclaredMethod(name, *parameterTypes)
      } catch (_: NoSuchMethodException) {
        current = current.superclass
      }
    }
    throw NoSuchMethodException("${this.name}.$name(${parameterTypes.joinToString { it.name }})")
  }
}

/**
 * Skiko's own Metal device wrapper (not the `id<MTLDevice>`, which is reached by sending
 * `adapter`), as a borrowed pointer. Skiko owns it; never retain or release it here.
 */
internal data class SkikoMetalDevice(val ptr: Long)

/**
 * Skiko's own `DirectXDevice` wrapper (not the `ID3D12Device`, which [SkikoDirect3DDeviceLayout]
 * reads out of it), as a borrowed pointer. Skiko owns it; never addref or release it here.
 */
internal data class SkikoDirect3DDevice(val ptr: Long)

/**
 * Reads the `ID3D12Device` out of Skiko's native `DirectXDevice`, which has no Java form, so its
 * fields must be located by byte offset.
 *
 * Offsets were read off Skiko's `skiko/src/awtMain/cpp/windows/directXRedrawer.cc` and Skia's
 * `include/gpu/ganesh/d3d/GrD3DBackendContext.h` at [VERIFIED_SKIKO_VERSION]: `DirectXDevice` is
 * non-virtual, `HWND hWnd` at 0, `GrD3DBackendContext` at 8 (`fAdapter`, `fDevice`, `fQueue`,
 * `fMemoryAllocator`, `fProtectedContext`, one pointer each), `device` at 48.
 *
 * Skiko assigns both copies of the device from the same local, so reading both and requiring
 * agreement detects a layout change. `WindowsDirect3DDeviceLayoutTest` fails the build when Skiko
 * moves off [VERIFIED_SKIKO_VERSION].
 */
internal object SkikoDirect3DDeviceLayout {
  /** The Skiko version whose sources these offsets were read from. */
  const val VERIFIED_SKIKO_VERSION: String = "0.144.6"

  /** `DirectXDevice::backendContext::fDevice`. */
  const val BACKEND_CONTEXT_DEVICE_OFFSET: Long = 16L

  /** `DirectXDevice::device`, the second copy of the same `ID3D12Device`. */
  const val DEVICE_OFFSET: Long = 48L

  /** How much of the object has to be addressable for both reads; the real one is far larger. */
  const val READ_SIZE: Long = DEVICE_OFFSET + Long.SIZE_BYTES

  /** The `ID3D12Device` inside the `DirectXDevice` [device] points at. */
  fun rawDevice(device: SkikoDirect3DDevice): Long =
    read(MemorySegment.ofAddress(device.ptr).reinterpret(READ_SIZE))

  /** Reads both copies out of [struct] and returns them if they agree. */
  fun read(struct: MemorySegment): Long {
    val fromBackendContext =
      struct.get(ValueLayout.ADDRESS, BACKEND_CONTEXT_DEVICE_OFFSET).address()
    val fromDeviceField = struct.get(ValueLayout.ADDRESS, DEVICE_OFFSET).address()
    if (fromBackendContext == 0L && fromDeviceField == 0L) {
      throw MlnFfiHostException(
        "Skiko's DirectXDevice holds no ID3D12Device. The host was probably asked for the " +
          "Direct3D device before Compose finished creating it."
      )
    }
    if (fromBackendContext != fromDeviceField) {
      throw MlnFfiHostException(
        "Skiko's DirectXDevice gave two different ID3D12Device pointers: " +
          "0x${fromBackendContext.toULong().toString(16)} at $BACKEND_CONTEXT_DEVICE_OFFSET and " +
          "0x${fromDeviceField.toULong().toString(16)} at $DEVICE_OFFSET. Skiko has changed the " +
          "layout of DirectXDevice since $VERIFIED_SKIKO_VERSION; re-read " +
          "skiko/src/awtMain/cpp/windows/directXRedrawer.cc and update SkikoDirect3DDeviceLayout."
      )
    }
    return fromBackendContext
  }
}
