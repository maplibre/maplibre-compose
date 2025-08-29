package org.maplibre.kmp.native.canvas

import org.lwjgl.PointerBuffer
import org.lwjgl.egl.EGL15.*
import org.lwjgl.opengl.WGL.*
import org.lwjgl.system.windows.GDI32

internal sealed interface GLContext {
  val isCurrent: Boolean

  fun bind()

  fun swap()

  fun activate()

  fun deactivate()

  fun dispose()

  companion object {
    fun create(jawtContext: JawtContext): GLContext {
      return when (jawtContext) {
        is JawtContext.Win32 -> WGL(jawtContext)
        else -> EGL(jawtContext)
      }
    }
  }

  class EGL(jawtContext: JawtContext) : GLContext {
    private var eglDisplay: Long
    private var eglSurface: Long
    private var eglContext: Long

    init {
      val (nativeDisplay, nativeWindow) =
        when (jawtContext) {
          is JawtContext.X11 -> Pair(jawtContext.display, jawtContext.drawable)
          // below are not used but could potentially work with ANGLE
          is JawtContext.Win32 -> Pair(jawtContext.hdc, jawtContext.hwnd)
          is JawtContext.MacOS -> {
            if (jawtContext.layer == 0L) jawtContext.initLayer()
            Pair(EGL_DEFAULT_DISPLAY, jawtContext.layer)
          }
        }

      eglDisplay = eglGetDisplay(nativeDisplay)
      if (eglDisplay == EGL_NO_DISPLAY) throwLastError()

      eglInitialize(eglDisplay, null as IntArray?, null) || throwLastError()
      bind()

      val config = PointerBuffer.allocateDirect(1)
      eglChooseConfig(
        eglDisplay,
        intArrayOf(
          EGL_SURFACE_TYPE,
          EGL_WINDOW_BIT,
          EGL_RED_SIZE,
          8,
          EGL_GREEN_SIZE,
          8,
          EGL_BLUE_SIZE,
          8,
          EGL_ALPHA_SIZE,
          8,
          EGL_DEPTH_SIZE,
          24,
          EGL_STENCIL_SIZE,
          8,
          EGL_RENDERABLE_TYPE,
          EGL_OPENGL_ES3_BIT,
          EGL_NONE,
        ),
        config,
        IntArray(1),
      ) || throwLastError()

      eglSurface =
        eglCreateWindowSurface(eglDisplay, config.get(0), nativeWindow, null as IntArray?)
      if (eglSurface == EGL_NO_SURFACE) throwLastError()

      eglContext =
        eglCreateContext(
          eglDisplay,
          config.get(0),
          EGL_NO_CONTEXT,
          intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE),
        )
      if (eglContext == EGL_NO_CONTEXT) throwLastError()
    }

    override val isCurrent: Boolean
      get() {
        check(eglContext != EGL_NO_CONTEXT)
        return eglGetCurrentContext() == eglContext
      }

    private fun throwLastError(): Nothing {
      val err = eglGetError()
      when (err) {
        EGL_SUCCESS -> error("EGL success")
        EGL_NOT_INITIALIZED -> error("EGL not initialized")
        EGL_BAD_ACCESS -> error("EGL bad access")
        EGL_BAD_ALLOC -> error("EGL bad alloc")
        EGL_BAD_ATTRIBUTE -> error("EGL bad attribute")
        EGL_BAD_CONFIG -> error("EGL bad config")
        EGL_BAD_CONTEXT -> error("EGL bad context")
        EGL_BAD_CURRENT_SURFACE -> error("EGL bad current surface")
        EGL_BAD_DISPLAY -> error("EGL bad display")
        EGL_BAD_MATCH -> error("EGL bad match")
        EGL_BAD_NATIVE_PIXMAP -> error("EGL bad native pixmap")
        EGL_BAD_NATIVE_WINDOW -> error("EGL bad native window")
        EGL_BAD_PARAMETER -> error("EGL bad parameter")
        EGL_BAD_SURFACE -> error("EGL bad surface")
        EGL_CONTEXT_LOST -> error("EGL context lost")
        else -> error("EGL error: $err")
      }
    }

    override fun dispose() {
      check(eglContext != EGL_NO_CONTEXT)
      deactivate()
      eglDestroyContext(eglDisplay, eglContext) || throwLastError()
      eglContext = EGL_NO_CONTEXT
      eglDestroySurface(eglDisplay, eglSurface) || throwLastError()
      eglSurface = EGL_NO_SURFACE
    }

    override fun bind() {
      eglBindAPI(EGL_OPENGL_ES_API) || throwLastError()
    }

    override fun swap() {
      check(eglSurface != EGL_NO_SURFACE)
      eglSwapBuffers(eglDisplay, eglSurface) || throwLastError()
    }

    override fun activate() {
      check(eglContext != EGL_NO_CONTEXT)
      eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext) || throwLastError()
    }

    override fun deactivate() {
      eglMakeCurrent(eglDisplay, 0L, 0L, 0L) || throwLastError()
    }
  }

  class WGL(jawtContext: JawtContext.Win32) : GLContext {
    private var hdc: Long = jawtContext.hdc
    private var hglrc: Long = 0

    init {
      TODO()
    }

    override val isCurrent: Boolean
      get() {
        check(hglrc != 0L)
        return hglrc == wglGetCurrentContext(null)
      }

    private fun throwLastError(): Nothing = TODO("Not yet implemented")

    override fun bind() {
      // no-op
    }

    override fun swap() {
      check(hdc != 0L)
      GDI32.SwapBuffers(null, hdc) || throwLastError()
    }

    override fun activate() {
      check(hglrc != 0L)
      wglMakeCurrent(null, hdc, hglrc) || throwLastError()
    }

    override fun deactivate() {
      wglMakeCurrent(null, 0L, 0L) || throwLastError()
    }

    override fun dispose() {
      check(hglrc != 0L)
      deactivate()
      wglDeleteContext(null, hglrc) || throwLastError()
      hglrc = 0L
    }
  }
}
