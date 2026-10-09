package org.maplibre.compose.desktop.skiko

import java.awt.Component
import java.awt.Container
import java.awt.Window
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.mlnffi.MlnFfiHostException

/**
 * Reaches into Compose Desktop's Skiko internals for the GPU objects it does not expose.
 *
 * Every reflective access in MapLibre Compose lives in this file.
 */
internal object SkikoReflection {
  // Cache metadata, never field values: Skiko replaces contexts during a window's lifetime.
  // ClassValue also lets a disposed class loader release its cached reflective members.
  private val fieldCache =
    object : ClassValue<ConcurrentHashMap<String, Field>>() {
      override fun computeValue(type: Class<*>) = ConcurrentHashMap<String, Field>()
    }

  const val SkiaLayerClass = "org.jetbrains.skiko.SkiaLayer"
  const val ComposeWindowClass = "androidx.compose.ui.awt.ComposeWindow"
  const val MetalRedrawerClass = "org.jetbrains.skiko.redrawer.MetalRedrawer"
  const val Direct3dRedrawerClass = "org.jetbrains.skiko.redrawer.Direct3DRedrawer"
  const val LinuxOpenGlRedrawerClass = "org.jetbrains.skiko.redrawer.LinuxOpenGLRedrawer"
  const val LinuxOpenGlRedrawerHelpersClass = "org.jetbrains.skiko.redrawer.LinuxOpenGLRedrawerKt"
  const val AwtLinuxDrawingSurfaceHelpersClass = "org.jetbrains.skiko.AWTLinuxDrawingSurfaceKt"
  const val Direct3dContextHandlerClass = "org.jetbrains.skiko.context.Direct3DContextHandler"
  const val MetalContextHandlerClass = "org.jetbrains.skiko.context.MetalContextHandler"
  const val ContextHandlerClass = "org.jetbrains.skiko.context.ContextHandler"

  /**
   * Skiko's Objective-C wrapper around the Metal device, and the selector on it that answers with
   * the `id<MTLDevice>` underneath. Neither is published API (private to Skiko's
   * `MetalRedrawer.mm`), so both are pinned by `SkikoMetalDeviceContractTest`.
   */
  const val SkikoMetalDeviceClass: String = "MetalDevice"

  const val SkikoMetalDeviceAdapter: String = "adapter"

  /** Finds only the Skia layer owned by [window], once that window is displayable. */
  fun findSkiaLayer(window: Window): Any? {
    if (!window.isDisplayable) return null
    return findSkiaLayerComponent(window) ?: findComposeWindowSkiaLayer(window)
  }

  /** Returns Skiko's native top-level window handle once [window] is displayable. */
  fun findNativeWindowHandle(window: Window): Long? = onEdt {
    val layer = findSkiaLayer(window) ?: return@onEdt null
    (layer.invokeNoArg("getWindowHandle") as? Long)?.takeIf { it != 0L }
  }

  fun requireRedrawer(layer: Any, expectedClass: String): Any {
    val redrawer =
      layer.invokeNoArg("getRedrawer\$skiko")
        ?: throw MlnFfiHostException("$SkiaLayerClass.getRedrawer\$skiko returned null")
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

  /** The monitor Skiko holds while Metal or Direct3D replays and submits a frame. */
  fun requireRenderLock(layer: Any, redrawerClass: String): Any =
    requireRedrawer(layer, redrawerClass).getField("drawLock")
      ?: throw MlnFfiHostException("$redrawerClass.drawLock was null")

  /**
   * The Direct3D device Compose renders with on Windows. Skiko stores it on the redrawer after the
   * first frame initializes the swap chain. Returns null until then.
   */
  fun findDirect3DDevice(redrawer: Any): SkikoDirect3DDevice? {
    val ptr = redrawer.getField("device") as? Long ?: return null
    if (ptr == 0L) return null
    return SkikoDirect3DDevice(ptr)
  }

  fun requireMetalContextHandler(layer: Any): Any =
    requireContextHandler(requireRedrawer(layer, MetalRedrawerClass), MetalRedrawerClass)

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
    return checkNotNull(result) { "EDT action did not execute" }.getOrThrow()
  }

  private fun findSkiaLayerComponent(window: Window): Any? =
    window.walkComponents().firstOrNull { isSkiaLayer(it) }

  private fun findComposeWindowSkiaLayer(window: Window): Any? {
    if (window.javaClass.name != ComposeWindowClass) return null
    return runCatching {
      val composePanel = window.getField("composePanel") ?: return@runCatching null
      val content =
        composePanel.invokeDeclaredNoArg("getContentComponent") ?: return@runCatching null
      if (isSkiaLayer(content)) content
      else (content as? Component)?.walkComponents()?.firstOrNull { isSkiaLayer(it) }
    }
      .getOrNull()
  }

  private fun isSkiaLayer(value: Any): Boolean = Class.forName(SkiaLayerClass).isInstance(value)

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

  fun Class<*>.staticInvoke(name: String, vararg args: Any?): Any? {
    val method =
      methods.firstOrNull { it.name == name && it.parameterCount == args.size }
        ?: throw NoSuchMethodException("$name/${args.size} on ${this.name}")
    return method.invoke(null, *args)
  }

  fun Class<*>.findField(name: String): Field {
    val cache = fieldCache.get(this)
    cache[name]?.let {
      return it
    }
    return cache.computeIfAbsent(name) { findUncachedField(it) }
  }

  private fun Class<*>.findUncachedField(name: String): Field {
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
 * `include/gpu/ganesh/d3d/GrD3DBackendContext.h` at [VerifiedSkikoVersion]: `DirectXDevice` is
 * non-virtual, `HWND hWnd` at 0, `GrD3DBackendContext` at 8 (`fAdapter`, `fDevice`, `fQueue`,
 * `fMemoryAllocator`, `fProtectedContext`, one pointer each), `device` at 48.
 *
 * Skiko assigns both copies of the device from the same local, so reading both and requiring
 * agreement detects a layout change. The first read also compares the classpath Skiko version to
 * [VerifiedSkikoVersion] and warns when they differ; `WindowsDirect3DDeviceLayoutTest` fails the
 * build on that same mismatch.
 */
internal object SkikoDirect3DDeviceLayout {
  /** The Skiko version whose sources these offsets were read from. */
  const val VerifiedSkikoVersion: String = "0.150.1"

  /** `DirectXDevice::backendContext::fDevice`. */
  const val BackendContextDeviceOffset: Long = 16L

  /** `DirectXDevice::device`, the second copy of the same `ID3D12Device`. */
  const val DeviceOffset: Long = 48L

  /** How much of the object has to be addressable for both reads; the real one is far larger. */
  const val ReadSize: Long = DeviceOffset + Long.SIZE_BYTES

  private val warnedAboutSkikoVersion = AtomicBoolean(false)
  private val logger = MapLog

  /**
   * Skiko's own version string from `org.jetbrains.skiko.Version`, or null if it is unavailable.
   */
  fun classpathSkikoVersion(): String? = runCatching {
    val version = Class.forName("org.jetbrains.skiko.Version")
    version.getMethod("getSkiko").invoke(version.getField("INSTANCE").get(null)) as String
  }
    .getOrNull()

  /**
   * Warns once when the classpath Skiko differs from [VerifiedSkikoVersion]. The offsets may still
   * be right; the two-pointer cross-check below catches a moved layout.
   */
  fun warnIfUnverifiedSkiko() {
    if (!warnedAboutSkikoVersion.compareAndSet(false, true)) return
    val skiko = classpathSkikoVersion() ?: return
    if (skiko == VerifiedSkikoVersion) return
    logger.w {
      "Skiko $skiko is on the classpath; the Windows Direct3D host verified its DirectXDevice " +
        "layout against $VerifiedSkikoVersion. Re-read " +
        "skiko/src/awtMain/cpp/windows/directXRedrawer.cc and update SkikoDirect3DDeviceLayout if " +
        "the struct moved."
    }
  }

  /** The `ID3D12Device` inside the `DirectXDevice` [device] points at. */
  fun rawDevice(device: SkikoDirect3DDevice): Long =
    read(MemorySegment.ofAddress(device.ptr).reinterpret(ReadSize))

  /** Reads both copies out of [struct] and returns them if they agree. */
  fun read(struct: MemorySegment): Long {
    warnIfUnverifiedSkiko()
    val fromBackendContext = struct.get(ValueLayout.ADDRESS, BackendContextDeviceOffset).address()
    val fromDeviceField = struct.get(ValueLayout.ADDRESS, DeviceOffset).address()
    if (fromBackendContext == 0L && fromDeviceField == 0L) {
      throw MlnFfiHostException(
        "Skiko's DirectXDevice holds no ID3D12Device. The host was probably asked for the " +
          "Direct3D device before Compose finished creating it."
      )
    }
    if (fromBackendContext != fromDeviceField) {
      throw MlnFfiHostException(
        "Skiko's DirectXDevice gave two different ID3D12Device pointers: " +
          "0x${fromBackendContext.toULong().toString(16)} at $BackendContextDeviceOffset and " +
          "0x${fromDeviceField.toULong().toString(16)} at $DeviceOffset. Skiko has changed the " +
          "layout of DirectXDevice since $VerifiedSkikoVersion; re-read " +
          "skiko/src/awtMain/cpp/windows/directXRedrawer.cc and update SkikoDirect3DDeviceLayout."
      )
    }
    return fromBackendContext
  }
}
