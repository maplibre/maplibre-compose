package org.maplibre.compose.mlnffi

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import androidx.compose.ui.graphics.drawscope.DrawScope
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.nativeffi.render.OpenGLClientApi
import org.maplibre.nativeffi.render.OpenGLContextOwnership

/** Deterministic offscreen GLES host for the shared real-map test corpus. */
internal class AndroidEglTestRenderDriver
private constructor(private val display: EGLDisplay, private val config: EGLConfig) :
  FfiTestRenderDriver {
  private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
  private val retiredSurfaces = mutableListOf<EGLSurface>()
  private var extent = MapExtent.Empty
  private var generation = 0L

  override val backends = RenderBackendPair(MapRenderBackend.OPENGL, ComposeRenderBackend.OPENGL)

  override fun <T> withRendererAccess(action: () -> T): T = action()

  override fun resize(extent: MapExtent) {
    ensureSurface(extent)
  }

  override fun acquireFrame(
    frameId: Long,
    extent: MapExtent,
    presentationTimeNanos: Long?,
  ): MlnFfiMapFrameAcquisition {
    ensureSurface(extent)
    return MlnFfiMapFrameAcquisition.Acquired(
      MlnFfiMapFrame(
        frameId = frameId,
        extent = extent,
        target =
          OpenGlSurfaceTarget(
            context =
              EglContextHandles(
                display = NativeHandle(display.nativeHandle),
                config = NativeHandle(config.nativeHandle),
                shareContext = NativeHandle(0L),
                getProcAddress = NativeHandle(0L),
                ownership = OpenGLContextOwnership.DEDICATED,
                clientApi = OpenGLClientApi.GLES,
              ),
            surface = NativeHandle(surface.nativeHandle),
            extent = extent,
            generation = generation,
          ),
        presentationTimeNanos = presentationTimeNanos,
      )
    )
  }

  override fun draw(
    scope: DrawScope,
    target: MlnFfiRenderTarget,
    destination: MlnFfiMapDestination,
  ): Boolean = false

  /** The producer renders directly into this EGL pbuffer, so there is no consumer-side bridge. */
  override fun present(target: MlnFfiRenderTarget): Boolean = true

  override fun readPixel(x: Int, y: Int): RgbaPixel {
    check(surface != EGL14.EGL_NO_SURFACE) { "No Android test frame has been rendered" }
    // A dedicated session keeps its context current on this thread after render. Symbol passes
    // leave the glyph atlas framebuffer bound, so read the pbuffer (default framebuffer). GLES
    // origin is the bottom left; fixture coordinates are the top left.
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    GLES20.glFinish()
    val bytes = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
    GLES30.glReadPixels(
      x,
      extent.physicalHeight - 1 - y,
      1,
      1,
      GLES30.GL_RGBA,
      GLES30.GL_UNSIGNED_BYTE,
      bytes,
    )
    check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "glReadPixels failed" }
    return RgbaPixel(
      red = bytes.get(0).toInt() and 0xff,
      green = bytes.get(1).toInt() and 0xff,
      blue = bytes.get(2).toInt() and 0xff,
      alpha = bytes.get(3).toInt() and 0xff,
    )
  }

  private fun ensureSurface(next: MapExtent) {
    if (surface != EGL14.EGL_NO_SURFACE && extent == next) return
    if (surface != EGL14.EGL_NO_SURFACE) {
      // A scale change makes MlnFfiMapSession close the old renderer after it receives the new
      // frame. That renderer still names the previous EGLSurface while closing, so keep old
      // generations alive until the fixture has closed every native render session.
      retiredSurfaces += surface
    }
    val attributes =
      intArrayOf(
        EGL14.EGL_WIDTH,
        next.physicalWidth,
        EGL14.EGL_HEIGHT,
        next.physicalHeight,
        EGL14.EGL_NONE,
      )
    surface = EGL14.eglCreatePbufferSurface(display, config, attributes, 0)
    check(surface != EGL14.EGL_NO_SURFACE) { eglFailure("create an Android test pbuffer") }
    extent = next
    generation++
  }

  override fun close() {
    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
    if (surface != EGL14.EGL_NO_SURFACE) {
      EGL14.eglDestroySurface(display, surface)
      surface = EGL14.EGL_NO_SURFACE
    }
    retiredSurfaces.forEach { EGL14.eglDestroySurface(display, it) }
    retiredSurfaces.clear()
    EGL14.eglTerminate(display)
    EGL14.eglReleaseThread()
  }

  private fun eglFailure(operation: String): String =
    "Failed to $operation (EGL error 0x${EGL14.eglGetError().toString(16)})"

  companion object {
    private const val EGL_OPENGL_ES3_BIT = 0x00000040

    fun create(): AndroidEglTestRenderDriver {
      val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
      check(display != EGL14.EGL_NO_DISPLAY) { "Android test EGL display is unavailable" }
      try {
        val version = IntArray(2)
        eglCheck(display, EGL14.eglInitialize(display, version, 0, version, 1), "initialize EGL")
        eglCheck(display, EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API), "bind the OpenGL ES API")
        return AndroidEglTestRenderDriver(display, chooseConfig(display))
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
          EGL14.EGL_PBUFFER_BIT,
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
        display,
        EGL14.eglChooseConfig(display, attributes, 0, configs, 0, configs.size, count, 0),
        "choose an EGL pbuffer config",
      )
      check(count[0] > 0 && configs[0] != null) { "No GLES 3 pbuffer config is available" }
      return configs[0]!!
    }

    private fun eglCheck(display: EGLDisplay, success: Boolean, operation: String) {
      check(success) { eglFailure(display, operation) }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun eglFailure(display: EGLDisplay, operation: String): String =
      "Failed to $operation (EGL error 0x${EGL14.eglGetError().toString(16)})"
  }
}
