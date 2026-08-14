package org.maplibre.compose.mlnffi

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.view.Surface
import org.maplibre.nativeffi.render.OpenGLClientApi
import org.maplibre.nativeffi.render.OpenGLContextOwnership

/**
 * EGL display, config, and window surface that a dedicated OpenGL surface session presents into.
 * The session creates the thread's context, so this object holds none of its own.
 */
internal class AndroidEglContext
private constructor(
  private val display: EGLDisplay,
  private val config: EGLConfig,
  private var windowSurface: EGLSurface,
) : AutoCloseable {

  val contextHandles: EglContextHandles
    get() =
      EglContextHandles(
        display = NativeHandle(display.nativeHandle),
        config = NativeHandle(config.nativeHandle),
        shareContext = NativeHandle(0L),
        // Android's EGL dispatch is supplied by the runtime; no host proc loader is needed.
        getProcAddress = NativeHandle(0L),
        ownership = OpenGLContextOwnership.DEDICATED,
        clientApi = OpenGLClientApi.GLES,
      )

  val surfaceHandle: NativeHandle
    get() = NativeHandle(windowSurface.nativeHandle)

  override fun close() {
    if (windowSurface != EGL14.EGL_NO_SURFACE) {
      EGL14.eglDestroySurface(display, windowSurface)
      windowSurface = EGL14.EGL_NO_SURFACE
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
        val windowSurface =
          EGL14.eglCreateWindowSurface(display, config, surface, WINDOW_ATTRIBUTES, 0)
        check(windowSurface != EGL14.EGL_NO_SURFACE) { eglFailure("create the EGL window surface") }
        return AndroidEglContext(display, config, windowSurface)
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
