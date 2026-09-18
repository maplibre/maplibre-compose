package org.maplibre.compose.desktop.bridge

import java.nio.ByteBuffer
import org.lwjgl.opengl.GL
import org.lwjgl.system.JNI.callPPI
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.windows.GDI32
import org.lwjgl.system.windows.PIXELFORMATDESCRIPTOR
import org.lwjgl.system.windows.User32
import org.lwjgl.system.windows.WNDCLASSEX
import org.lwjgl.system.windows.WinBase
import org.lwjgl.system.windows.WindowProc

internal class WindowsGlDrawable
private constructor(
  private val module: Long,
  private val className: String,
  private val windowProc: WindowProc,
  private var window: Long,
  var deviceContext: Long,
) : AutoCloseable {
  override fun close() {
    if (deviceContext != 0L) User32.ReleaseDC(window, deviceContext)
    if (window != 0L) User32.DestroyWindow(null, window)
    User32.UnregisterClass(null, className, module)
    windowProc.free()
    deviceContext = 0L
    window = 0L
  }

  companion object {
    fun create(): WindowsGlDrawable {
      val module = WinBase.GetModuleHandle(null, null as ByteBuffer?)
      check(module != 0L) { "Could not find the current Windows module" }
      val className = "MapLibreComposeOpenGL-${System.nanoTime()}"
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
          // Mesa can be loaded beside the application without being registered as a system ICD.
          // Use the same OpenGL library for pixel formats and WGL context creation.
          val functions = checkNotNull(GL.getFunctionProvider()) { "OpenGL is not loaded" }
          val choosePixelFormat = functions.getFunctionAddress("wglChoosePixelFormat")
          val setPixelFormat = functions.getFunctionAddress("wglSetPixelFormat")
          check(choosePixelFormat != 0L && setPixelFormat != 0L) {
            "The OpenGL library does not expose WGL pixel-format functions"
          }
          val format = callPPI(deviceContext, pixel.address(), choosePixelFormat)
          check(format != 0) { "Could not choose an offscreen OpenGL pixel format" }
          check(callPPI(deviceContext, format, pixel.address(), setPixelFormat) != 0) {
            "Could not set the offscreen OpenGL pixel format"
          }
        }
        return WindowsGlDrawable(module, className, windowProc, window, deviceContext)
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
