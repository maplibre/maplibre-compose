package org.maplibre.compose.mlnffi

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.view.Surface

/** EGL objects that outlive one render session and present into an embedded Android surface. */
internal class AndroidEglContext
private constructor(
  private val display: EGLDisplay,
  private val config: EGLConfig,
  private var shareContext: EGLContext,
  private var windowSurface: EGLSurface,
) : AutoCloseable {

  val contextHandles: EglContextHandles
    get() =
      EglContextHandles(
        display = NativeHandle(display.nativeHandle),
        config = NativeHandle(config.nativeHandle),
        shareContext = NativeHandle(shareContext.nativeHandle),
        // Android's EGL dispatch is supplied by the runtime; no host proc loader is needed.
        getProcAddress = NativeHandle(0L),
      )

  val surfaceHandle: NativeHandle
    get() = NativeHandle(windowSurface.nativeHandle)

  override fun close() {
    if (windowSurface != EGL14.EGL_NO_SURFACE) {
      EGL14.eglDestroySurface(display, windowSurface)
      windowSurface = EGL14.EGL_NO_SURFACE
    }
    if (shareContext != EGL14.EGL_NO_CONTEXT) {
      EGL14.eglDestroyContext(display, shareContext)
      shareContext = EGL14.EGL_NO_CONTEXT
    }
    EGL14.eglTerminate(display)
    EGL14.eglReleaseThread()
  }

  companion object {
    private const val EGL_OPENGL_ES3_BIT = 0x00000040
    private val WINDOW_ATTRIBUTES = intArrayOf(EGL14.EGL_NONE)

    fun create(surface: Surface): AndroidEglContext {
      val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
      check(display != EGL14.EGL_NO_DISPLAY) { "EGL display is unavailable" }

      try {
        val version = IntArray(2)
        eglCheck(EGL14.eglInitialize(display, version, 0, version, 1), "initialize EGL")
        eglCheck(EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API), "bind the OpenGL ES API")

        val config = chooseConfig(display)
        val contextAttributes = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        val context =
          EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttributes, 0)
        check(context != EGL14.EGL_NO_CONTEXT) { eglFailure("create the EGL share context") }

        val windowSurface =
          EGL14.eglCreateWindowSurface(display, config, surface, WINDOW_ATTRIBUTES, 0)
        if (windowSurface == EGL14.EGL_NO_SURFACE) {
          EGL14.eglDestroyContext(display, context)
          error(eglFailure("create the EGL window surface"))
        }
        return AndroidEglContext(display, config, context, windowSurface)
      } catch (error: Throwable) {
        EGL14.eglTerminate(display)
        throw error
      }
    }

    private fun chooseConfig(display: EGLDisplay): EGLConfig {
      val attributes =
        intArrayOf(
          EGL14.EGL_RENDERABLE_TYPE,
          EGL_OPENGL_ES3_BIT,
          EGL14.EGL_SURFACE_TYPE,
          EGL14.EGL_WINDOW_BIT,
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
      val configs = arrayOfNulls<EGLConfig>(1)
      val count = IntArray(1)
      eglCheck(
        EGL14.eglChooseConfig(display, attributes, 0, configs, 0, configs.size, count, 0),
        "choose an EGL config",
      )
      check(count[0] > 0 && configs[0] != null) {
        "No EGL config supports OpenGL ES 3 window rendering"
      }
      return configs[0]!!
    }

    private fun eglCheck(success: Boolean, operation: String) {
      check(success) { eglFailure(operation) }
    }

    private fun eglFailure(operation: String): String =
      "Failed to $operation (EGL error 0x${EGL14.eglGetError().toString(16)})"
  }
}
