package org.maplibre.compose.mlnffi

import android.opengl.EGL14
import android.opengl.EGLDisplay

/** The process-owned default EGL display shared by every Android OpenGL renderer. */
internal object AndroidEglDisplay {
  val default: EGLDisplay by lazy {
    val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    check(display != EGL14.EGL_NO_DISPLAY) { "EGL display is unavailable" }
    val version = IntArray(2)
    check(EGL14.eglInitialize(display, version, 0, version, 1)) { "Could not initialize EGL" }
    display
  }
}
