package org.maplibre.kmp.native.canvas

import java.nio.IntBuffer
import org.lwjgl.PointerBuffer
import org.lwjgl.egl.EGL15.*
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GLX14.*
import org.lwjgl.opengl.GLXARBCreateContext.*
import org.lwjgl.opengl.GLXARBCreateContextProfile.*
import org.lwjgl.opengl.WGL.*
import org.lwjgl.opengl.WGLARBCreateContext.*
import org.lwjgl.opengl.WGLARBCreateContextProfile.*
import org.lwjgl.system.linux.X11
import org.lwjgl.system.windows.GDI32
import org.lwjgl.system.windows.PIXELFORMATDESCRIPTOR

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
        is JawtContext.X11 -> EGL(jawtContext)
        // MacOS is not implemented yet, but EGL could potentially work with ANGLE
        is JawtContext.MacOS -> EGL(jawtContext)
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
          EGL_OPENGL_ES2_BIT,
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
          intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE),
        )
      if (eglContext == EGL_NO_CONTEXT) throwLastError()

      activate()
      deactivate()
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
      check(
        eglDisplay != EGL_NO_DISPLAY && eglSurface != EGL_NO_SURFACE && eglContext != EGL_NO_CONTEXT
      )
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
      check(eglDisplay != EGL_NO_DISPLAY && eglSurface != EGL_NO_SURFACE)
      eglSwapBuffers(eglDisplay, eglSurface) || throwLastError()
    }

    override fun activate() {
      check(
        eglDisplay != EGL_NO_DISPLAY && eglSurface != EGL_NO_SURFACE && eglContext != EGL_NO_CONTEXT
      )
      eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext) || throwLastError()
    }

    override fun deactivate() {
      check(eglDisplay != EGL_NO_DISPLAY)
      eglMakeCurrent(eglDisplay, 0L, 0L, 0L) || throwLastError()
    }
  }

  class GLX(jawtContext: JawtContext.X11) : GLContext {
    private var display: Long
    private var drawable: Long
    private var context: Long

    init {
      display = jawtContext.display
      drawable = jawtContext.drawable

      val configs =
        glXChooseFBConfig(
          display,
          X11.XDefaultScreen(display),
          intArrayOf(
            GLX_X_RENDERABLE,
            1,
            GLX_DRAWABLE_TYPE,
            GLX_WINDOW_BIT,
            GLX_RENDER_TYPE,
            GLX_RGBA_BIT,
            GLX_X_VISUAL_TYPE,
            GLX_TRUE_COLOR,
            GLX_RED_SIZE,
            8,
            GLX_GREEN_SIZE,
            8,
            GLX_BLUE_SIZE,
            8,
            GLX_ALPHA_SIZE,
            8,
            GLX_DEPTH_SIZE,
            24,
            GLX_STENCIL_SIZE,
            8,
            GLX_DOUBLEBUFFER,
            1,
            0,
          ),
        )
      if (configs == null || configs.capacity() == 0) error("no GLX FB config found")

      context =
        glXCreateContextAttribsARB(
          display,
          configs.get(0),
          0L,
          true,
          intArrayOf(
            GLX_CONTEXT_MAJOR_VERSION_ARB,
            3,
            GLX_CONTEXT_MINOR_VERSION_ARB,
            0,
            GLX_CONTEXT_PROFILE_MASK_ARB,
            GLX_CONTEXT_CORE_PROFILE_BIT_ARB,
            0,
          ),
        )
      if (context == 0L) error("failed to create GLX context")
    }

    override val isCurrent: Boolean
      get() = context != 0L && glXGetCurrentContext() == context

    override fun bind() {}

    override fun swap() {
      check(display != 0L && drawable != 0L)
      glXSwapBuffers(display, drawable)
    }

    override fun activate() {
      check(display != 0L && drawable != 0L && context != 0L)
      glXMakeCurrent(display, drawable, context) || error("failed to make GLX context current")
      GL.createCapabilities()
    }

    override fun deactivate() {
      check(display != 0L)
      glXMakeCurrent(display, 0L, 0L) || error("failed to release GLX context")
    }

    override fun dispose() {
      check(context != 0L)
      deactivate()
      glXDestroyContext(display, context)
      context = 0L
      drawable = 0L
      display = 0L
    }
  }

  class WGL(jawtContext: JawtContext.Win32) : GLContext {
    private var hdc: Long = jawtContext.hdc
    private var hglrc: Long = 0

    private val lastError: IntBuffer = IntBuffer.allocate(1)

    init {
      check(hdc != 0L)

      val pfd =
        PIXELFORMATDESCRIPTOR.create().apply {
          nSize(sizeof().toShort())
          nVersion(1)
          dwFlags(GDI32.PFD_DRAW_TO_WINDOW or GDI32.PFD_SUPPORT_OPENGL or GDI32.PFD_DOUBLEBUFFER)
          iPixelType(GDI32.PFD_TYPE_RGBA)
          cColorBits(24)
          cAlphaBits(8)
          cDepthBits(24)
          cStencilBits(8)
        }

      val pixelFormat = GDI32.ChoosePixelFormat(lastError, hdc, pfd)
      if (pixelFormat == 0) throwLastError()
      GDI32.SetPixelFormat(lastError, hdc, pixelFormat, pfd) || throwLastError()

      val tempContext = wglCreateContext(lastError, hdc)
      if (tempContext == 0L) throwLastError()
      wglMakeCurrent(lastError, hdc, tempContext) || throwLastError()

      hglrc =
        wglCreateContextAttribsARB(
          hdc,
          0L,
          intArrayOf(
            WGL_CONTEXT_MAJOR_VERSION_ARB,
            3,
            WGL_CONTEXT_MINOR_VERSION_ARB,
            0,
            WGL_CONTEXT_PROFILE_MASK_ARB,
            WGL_CONTEXT_CORE_PROFILE_BIT_ARB,
            0,
          ),
        )
      if (hglrc == 0L) error("failed to create WGL context")

      deactivate() // tempContext is current, so release it
      wglDeleteContext(lastError, tempContext) || throwLastError()
    }

    override val isCurrent: Boolean
      get() {
        check(hglrc != 0L)
        return hglrc == wglGetCurrentContext(lastError)
      }

    private fun throwLastError(): Nothing {
      // should figure out the possible error codes, but I'm having trouble finding docs
      error("WGL error: ${lastError.get(0)}")
    }

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
      GL.createCapabilities()
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
