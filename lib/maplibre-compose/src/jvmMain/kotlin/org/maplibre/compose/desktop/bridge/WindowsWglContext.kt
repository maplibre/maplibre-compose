package org.maplibre.compose.desktop.bridge

import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.glFinish
import org.lwjgl.opengl.WGL
import org.lwjgl.opengl.WGLARBCreateContext
import org.lwjgl.system.MemoryStack
import org.maplibre.compose.mlnffi.NativeHandle
import org.maplibre.compose.mlnffi.WglContextHandles

/** Owns a hidden Win32 drawable and WGL context on the map renderer thread. */
internal class WindowsWglContext private constructor() : AutoCloseable {
  private var drawable: WindowsGlDrawable? = null
  private val dc: Long
    get() = checkNotNull(drawable).deviceContext

  private var context = 0L
  private var capabilities: org.lwjgl.opengl.GLCapabilities? = null
  val handles
    get() = WglContextHandles(NativeHandle(dc), NativeHandle(context), NativeHandle(0))

  fun makeCurrent() {
    check(WGL.nwglMakeCurrent(0L, dc, context) != 0) { "wglMakeCurrent failed" }
    capabilities?.let(GL::setCapabilities) ?: run { capabilities = GL.createCapabilities() }
  }

  fun waitIdle() {
    makeCurrent()
    glFinish()
  }

  private fun initialize() {
    drawable = WindowsGlDrawable.create()
    run {
      context = WGL.nwglCreateContext(0L, dc)
      check(context != 0L) { "wglCreateContext failed" }
      makeCurrent()
      val wgl = GL.createCapabilitiesWGL()
      check(wgl.WGL_ARB_create_context) { "The OpenGL driver cannot create an OpenGL 3 context" }
      MemoryStack.stackPush().use { stack ->
        val modern =
          WGLARBCreateContext.wglCreateContextAttribsARB(
            dc,
            0L,
            stack.ints(
              WGLARBCreateContext.WGL_CONTEXT_MAJOR_VERSION_ARB,
              3,
              WGLARBCreateContext.WGL_CONTEXT_MINOR_VERSION_ARB,
              3,
              0,
            ),
          )
        check(modern != 0L) { "wglCreateContextAttribsARB(OpenGL 3.3) failed" }
        WGL.nwglMakeCurrent(0L, 0L, 0L)
        WGL.nwglDeleteContext(0L, context)
        context = modern
        capabilities = null
        makeCurrent()
      }
    }
  }

  override fun close() {
    if (context != 0L) {
      runCatching { waitIdle() }
      WGL.nwglMakeCurrent(0L, 0L, 0L)
      WGL.nwglDeleteContext(0L, context)
      context = 0
      GL.setCapabilities(null)
    }
    drawable?.close()
    drawable = null
  }

  companion object {
    fun create(): WindowsWglContext {
      val context = WindowsWglContext()
      try {
        context.initialize()
        return context
      } catch (error: Throwable) {
        runCatching { context.close() }.onFailure(error::addSuppressed)
        throw error
      }
    }
  }
}
