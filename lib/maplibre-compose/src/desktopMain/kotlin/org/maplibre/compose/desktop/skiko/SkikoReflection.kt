package org.maplibre.compose.desktop.skiko

import java.awt.Component
import java.awt.Container
import java.awt.Window
import java.lang.reflect.Field
import java.lang.reflect.Method
import javax.swing.SwingUtilities

/**
 * Reaches into Compose Desktop's Skiko internals for the GPU objects it does not expose.
 *
 * Compose Desktop offers no supported way to obtain the graphics context it renders with, so the
 * default host reads it reflectively. Every reflective access in MapLibre Compose lives in this
 * file: no map, style, input, or resource code reflects into Skiko, so when Compose gains a
 * supported hook only this file changes.
 *
 * Failures are reported as [DesktopHostException] naming the expected and observed classes, so a
 * Compose upgrade that moves something produces a diagnostic rather than a `NoSuchFieldException`.
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

  /** Every Skiko class the default host depends on. Checked by the reflection contract test. */
  val REQUIRED_CLASSES: List<String> =
    listOf(
      SKIA_LAYER_CLASS,
      COMPOSE_WINDOW_CLASS,
      METAL_REDRAWER_CLASS,
      DIRECT3D_REDRAWER_CLASS,
      LINUX_OPENGL_REDRAWER_CLASS,
      LINUX_OPENGL_REDRAWER_HELPERS_CLASS,
      AWT_LINUX_DRAWING_SURFACE_HELPERS_CLASS,
      DIRECT3D_CONTEXT_HANDLER_CLASS,
      METAL_CONTEXT_HANDLER_CLASS,
      CONTEXT_HANDLER_CLASS,
    )

  /** Finds the live `SkiaLayer` backing the current Compose window, if there is one. */
  fun findSkiaLayer(): Any? = findSkiaLayerComponent() ?: findComposeWindowSkiaLayer()

  fun requireSkiaLayer(): Any =
    findSkiaLayer()
      ?: throw DesktopHostException("Could not find a live $SKIA_LAYER_CLASS. ${describeWindows()}")

  /** The redrawer for [layer], checked against [expectedClass]. */
  fun requireRedrawer(layer: Any, expectedClass: String): Any {
    val redrawer =
      layer.invokeNoArg("getRedrawer\$skiko")
        ?: throw DesktopHostException("$SKIA_LAYER_CLASS.getRedrawer\$skiko returned null")
    if (!Class.forName(expectedClass).isAssignableFrom(redrawer.javaClass)) {
      throw DesktopHostException(
        "Skiko redrawer was ${redrawer.javaClass.name}, expected $expectedClass. " +
          "Compose is probably rendering with a different backend than the map host assumed."
      )
    }
    return redrawer
  }

  fun requireContextHandler(redrawer: Any, redrawerClass: String): Any =
    redrawer.getField("contextHandler")
      ?: throw DesktopHostException("$redrawerClass.contextHandler was null")

  /**
   * The Direct3D device Compose renders with on Windows.
   *
   * Skiko keeps this on the redrawer rather than the context handler, and only after the first
   * frame has initialized the swap chain, so a null or zero pointer here means the host was asked
   * for a device before Compose had one.
   */
  fun requireDirect3DDevice(): SkikoDirect3DDevice = onEdt {
    val layer = requireSkiaLayer()
    val redrawer = requireRedrawer(layer, DIRECT3D_REDRAWER_CLASS)
    val ptr =
      redrawer.getField("device") as? Long
        ?: throw DesktopHostException("$DIRECT3D_REDRAWER_CLASS.device was null")
    if (ptr == 0L) throw DesktopHostException("$DIRECT3D_REDRAWER_CLASS.device was zero")
    SkikoDirect3DDevice(ptr)
  }

  /** The context handler of the Metal redrawer backing [layer]. */
  fun requireMetalContextHandler(layer: Any): Any =
    requireContextHandler(requireRedrawer(layer, METAL_REDRAWER_CLASS), METAL_REDRAWER_CLASS)

  /**
   * The Metal device Compose renders with on macOS.
   *
   * MapLibre must allocate its texture on the same device Skia will sample it from, so the host
   * borrows Skiko's rather than creating one. Skiko stores it as a `MetalDevice` value class, which
   * the JVM sees as a plain `long` field once inlined — hence the widening below, which also covers
   * the boxed shape in case a future Skiko stops inlining it.
   */
  fun requireMetalDevice(): SkikoMetalDevice = onEdt {
    val contextHandler = requireMetalContextHandler(requireSkiaLayer())
    val device =
      contextHandler.getField("device")
        ?: throw DesktopHostException(
          "${contextHandler.javaClass.name}.device was null; " +
            "Skiko has not created the Metal device yet"
        )
    val ptr =
      when (device) {
        is Long -> device
        else ->
          device.getField("ptr") as? Long
            ?: device.invokeNoArg("getPtr") as? Long
            ?: throw DesktopHostException(
              "${device.javaClass.name} did not expose the Skiko MetalDevice pointer"
            )
      }
    if (ptr == 0L) {
      throw DesktopHostException("${contextHandler.javaClass.name}.device.ptr was zero")
    }
    SkikoMetalDevice(ptr)
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
 * Skiko's native Metal device object, as a borrowed pointer.
 *
 * This is Skiko's own wrapper object, not the `id<MTLDevice>` itself; the device is reached from it
 * by sending `adapter`. Skiko owns it, so it is never retained or released here.
 */
internal data class SkikoMetalDevice(val ptr: Long)

/**
 * Skiko's native Direct3D device object, as a borrowed pointer.
 *
 * This is Skiko's own `DirectXDevice` wrapper, not the `ID3D12Device` itself; the device is read
 * out of it at a fixed offset. Skiko owns it, so it is never addrefed or released here.
 */
internal data class SkikoDirect3DDevice(val ptr: Long)
