package org.maplibre.compose.map

import java.nio.ByteBuffer
import org.lwjgl.PointerBuffer
import org.lwjgl.egl.EGL
import org.lwjgl.egl.EGL14
import org.lwjgl.egl.EGL15
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.windows.GDI32
import org.lwjgl.system.windows.PIXELFORMATDESCRIPTOR
import org.lwjgl.system.windows.User32
import org.lwjgl.system.windows.WNDCLASSEX
import org.lwjgl.system.windows.WinBase
import org.lwjgl.system.windows.WindowProc
import org.maplibre.nativeffi.render.EglContextDescriptor
import org.maplibre.nativeffi.render.NativePointer
import org.maplibre.nativeffi.render.OpenGLClientApi
import org.maplibre.nativeffi.render.OpenGLContextDescriptor
import org.maplibre.nativeffi.render.OpenGLContextOwnership
import org.maplibre.nativeffi.render.WglContextDescriptor

/** A platform display connection from which the FFI owns a private OpenGL context. */
internal sealed interface DesktopOpenGlSnapshotContext : AutoCloseable {
  val descriptor: OpenGLContextDescriptor

  companion object {
    fun create(os: String): DesktopOpenGlSnapshotContext =
      when {
        os.contains("linux") -> EglSnapshotContext.create()
        os.contains("windows") -> WglSnapshotContext.create()
        else -> throw UnsupportedOperationException("No offscreen OpenGL context provider for $os")
      }
  }
}

private class EglSnapshotContext private constructor(display: Long, config: Long) :
  DesktopOpenGlSnapshotContext {
  override val descriptor: OpenGLContextDescriptor =
    EglContextDescriptor(
      display = NativePointer.ofAddress(display),
      config = NativePointer.ofAddress(config),
      shareContext = NativePointer.NULL_POINTER,
      getProcAddress = NativePointer.NULL_POINTER,
      clientApi = OpenGLClientApi.GL,
      ownership = OpenGLContextOwnership.DEDICATED,
    )

  // EGL display connections are process-shared. The FFI owns and destroys this snapshotter's
  // context, while the connection remains initialized for other maps and snapshotters.
  override fun close() = Unit

  companion object {
    private const val EGL_PLATFORM_SURFACELESS_MESA = 0x31DD

    @Suppress("SENSELESS_COMPARISON")
    private val library by lazy {
      if (EGL.getFunctionProvider() == null) EGL.create()
    }

    private val connection: Pair<Long, Long> by lazy {
      MemoryStack.stackPush().use { stack ->
        val display = initializedDisplay(stack)
        try {
          val configs = stack.mallocPointer(1)
          val configCount = stack.mallocInt(1)
          val attributes =
            stack.ints(
              EGL14.EGL_SURFACE_TYPE,
              EGL14.EGL_PBUFFER_BIT,
              EGL14.EGL_RENDERABLE_TYPE,
              EGL14.EGL_OPENGL_BIT,
              EGL14.EGL_RED_SIZE,
              8,
              EGL14.EGL_GREEN_SIZE,
              8,
              EGL14.EGL_BLUE_SIZE,
              8,
              EGL14.EGL_ALPHA_SIZE,
              8,
              EGL14.EGL_DEPTH_SIZE,
              24,
              EGL14.EGL_STENCIL_SIZE,
              8,
              EGL14.EGL_NONE,
            )
          check(
            EGL14.eglChooseConfig(display, attributes, configs, configCount) && configCount[0] > 0
          ) {
            "No EGL config supports OpenGL pbuffer rendering"
          }
          display to configs[0]
        } catch (error: Throwable) {
          EGL14.eglTerminate(display)
          throw error
        }
      }
    }

    fun create(): EglSnapshotContext {
      library
      check(EGL14.eglBindAPI(EGL14.EGL_OPENGL_API)) { "Could not bind EGL OpenGL" }
      val (display, config) = connection
      return EglSnapshotContext(display, config)
    }

    private fun initializedDisplay(stack: MemoryStack): Long {
      val surfaceless = runCatching {
        EGL15.eglGetPlatformDisplay(
          EGL_PLATFORM_SURFACELESS_MESA,
          EGL14.EGL_DEFAULT_DISPLAY,
          null as PointerBuffer?,
        )
      }
        .getOrDefault(EGL14.EGL_NO_DISPLAY)
      if (surfaceless != EGL14.EGL_NO_DISPLAY && initialize(surfaceless, stack)) {
        return surfaceless
      }
      val fallback = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
      check(fallback != EGL14.EGL_NO_DISPLAY && initialize(fallback, stack)) {
        "No EGL display is available for offscreen snapshots"
      }
      return fallback
    }

    private fun initialize(display: Long, stack: MemoryStack): Boolean {
      val major = stack.mallocInt(1)
      val minor = stack.mallocInt(1)
      if (EGL14.eglInitialize(display, major, minor)) return true
      EGL14.eglTerminate(display)
      return false
    }
  }
}

private class WglSnapshotContext
private constructor(
  private val module: Long,
  private val className: String,
  private val windowProc: WindowProc,
  private var window: Long,
  private var deviceContext: Long,
) : DesktopOpenGlSnapshotContext {
  override val descriptor: OpenGLContextDescriptor =
    WglContextDescriptor(
      deviceContext = NativePointer.ofAddress(deviceContext),
      shareContext = NativePointer.NULL_POINTER,
      getProcAddress = NativePointer.NULL_POINTER,
      ownership = OpenGLContextOwnership.DEDICATED,
    )

  override fun close() {
    if (deviceContext != 0L) User32.ReleaseDC(window, deviceContext)
    if (window != 0L) User32.DestroyWindow(null, window)
    User32.UnregisterClass(null, className, module)
    windowProc.free()
    deviceContext = 0L
    window = 0L
  }

  companion object {
    fun create(): WglSnapshotContext {
      val module = WinBase.GetModuleHandle(null, null as ByteBuffer?)
      check(module != 0L) { "Could not find the current Windows module" }
      val className = "MapLibreComposeSnapshot-${System.nanoTime()}"
      val windowProc = WindowProc.create { window, message, word, long ->
        User32.DefWindowProc(window, message, word, long)
      }
      var registered = false
      var window = 0L
      var deviceContext = 0L
      try {
        MemoryStack.stackPush().use { stack ->
          val windowClass =
            WNDCLASSEX.calloc(stack)
              .cbSize(WNDCLASSEX.SIZEOF)
              .style(User32.CS_OWNDC)
              .lpfnWndProc(windowProc)
              .hInstance(module)
              .lpszClassName(stack.UTF16(className, true))
          check(User32.RegisterClassEx(null, windowClass).toInt() != 0) {
            "Could not register the offscreen OpenGL window class"
          }
          registered = true
          window =
            User32.CreateWindowEx(
              null,
              0,
              className,
              "",
              0,
              0,
              0,
              1,
              1,
              0L,
              0L,
              module,
              0L,
            )
          check(window != 0L) { "Could not create the offscreen OpenGL window" }
          deviceContext = User32.GetDC(window)
          check(deviceContext != 0L) { "Could not acquire the offscreen OpenGL device context" }
          val pixel =
            PIXELFORMATDESCRIPTOR.calloc(stack)
              .nSize(PIXELFORMATDESCRIPTOR.SIZEOF.toShort())
              .nVersion(1)
              .dwFlags(GDI32.PFD_DRAW_TO_WINDOW or GDI32.PFD_SUPPORT_OPENGL)
              .iPixelType(GDI32.PFD_TYPE_RGBA)
              .cColorBits(32)
              .cAlphaBits(8)
              .cDepthBits(24)
              .cStencilBits(8)
              .iLayerType(GDI32.PFD_MAIN_PLANE)
          val format = GDI32.ChoosePixelFormat(null, deviceContext, pixel)
          check(format != 0) { "Could not choose an offscreen OpenGL pixel format" }
          check(GDI32.SetPixelFormat(null, deviceContext, format, pixel)) {
            "Could not set the offscreen OpenGL pixel format"
          }
        }
        return WglSnapshotContext(module, className, windowProc, window, deviceContext)
      } catch (error: Throwable) {
        if (deviceContext != 0L) User32.ReleaseDC(window, deviceContext)
        if (window != 0L) User32.DestroyWindow(null, window)
        if (registered) User32.UnregisterClass(null, className, module)
        windowProc.free()
        throw error
      }
    }
  }
}
