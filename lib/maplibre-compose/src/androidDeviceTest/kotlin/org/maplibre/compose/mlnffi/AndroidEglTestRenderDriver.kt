package org.maplibre.compose.mlnffi

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import androidx.compose.ui.graphics.drawscope.DrawScope
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.nativeffi.render.OpenGLClientApi
import org.maplibre.nativeffi.render.OpenGLContextOwnership

/**
 * Offscreen GLES host for the shared real-map test corpus.
 *
 * Production Android presents into a window surface. This driver uses an [ImageReader] window
 * surface so the dedicated session can swap and tests can [PixelCopy] the same buffer, including
 * symbol layers that sample the glyph atlas.
 */
internal class AndroidEglTestRenderDriver
private constructor(
  private val display: EGLDisplay,
  private val config: EGLConfig,
  private val pixelCopyThread: HandlerThread,
) : FfiTestRenderDriver {
  private val pixelCopyHandler = Handler(pixelCopyThread.looper)
  private var imageReader: ImageReader? = null
  private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
  private val retiredSurfaces = mutableListOf<EGLSurface>()
  private val retiredReaders = mutableListOf<ImageReader>()
  private var extent = MapExtent.Empty
  private var generation = 0L
  private var snapshot: Bitmap? = null

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

  override fun draw(scope: DrawScope, target: MlnFfiRenderTarget): Boolean = false

  override fun present(target: MlnFfiRenderTarget): Boolean {
    val reader = imageReader ?: return false
    val bitmap =
      Bitmap.createBitmap(extent.physicalWidth, extent.physicalHeight, Bitmap.Config.ARGB_8888)
    val latch = CountDownLatch(1)
    var copyResult = PixelCopy.ERROR_UNKNOWN
    PixelCopy.request(
      reader.surface,
      bitmap,
      { result ->
        copyResult = result
        latch.countDown()
      },
      pixelCopyHandler,
    )
    check(latch.await(PIXEL_COPY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      "Timed out copying an Android test frame"
    }
    check(copyResult == PixelCopy.SUCCESS) { "PixelCopy failed with result $copyResult" }
    snapshot?.recycle()
    snapshot = bitmap
    return true
  }

  override fun readPixel(x: Int, y: Int): RgbaPixel {
    val bitmap = snapshot
    check(bitmap != null) { "No Android test frame has been rendered" }
    val color = bitmap.getPixel(x, y)
    return RgbaPixel(
      red = (color ushr 16) and 0xff,
      green = (color ushr 8) and 0xff,
      blue = color and 0xff,
      alpha = (color ushr 24) and 0xff,
    )
  }

  private fun ensureSurface(next: MapExtent) {
    if (surface != EGL14.EGL_NO_SURFACE && extent == next) return
    if (surface != EGL14.EGL_NO_SURFACE) {
      // A scale change makes MlnFfiMapSession close the old renderer after it receives the new
      // frame. That renderer still names the previous EGLSurface while closing, so keep old
      // generations alive until the fixture has closed every native render session.
      retiredSurfaces += surface
      retiredReaders += checkNotNull(imageReader)
      surface = EGL14.EGL_NO_SURFACE
      imageReader = null
    }
    val reader = createImageReader(next.physicalWidth, next.physicalHeight)
    val windowSurface =
      EGL14.eglCreateWindowSurface(
        display,
        config,
        reader.surface,
        intArrayOf(EGL14.EGL_NONE),
        0,
      )
    check(windowSurface != EGL14.EGL_NO_SURFACE) {
      eglFailure("create an Android test window surface")
    }
    imageReader = reader
    surface = windowSurface
    extent = next
    generation++
    snapshot?.recycle()
    snapshot = null
  }

  override fun close() {
    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
    if (surface != EGL14.EGL_NO_SURFACE) {
      EGL14.eglDestroySurface(display, surface)
      surface = EGL14.EGL_NO_SURFACE
    }
    retiredSurfaces.forEach { EGL14.eglDestroySurface(display, it) }
    retiredSurfaces.clear()
    imageReader?.close()
    imageReader = null
    retiredReaders.forEach { it.close() }
    retiredReaders.clear()
    snapshot?.recycle()
    snapshot = null
    pixelCopyThread.quitSafely()
    EGL14.eglTerminate(display)
    EGL14.eglReleaseThread()
  }

  private fun eglFailure(operation: String): String =
    "Failed to $operation (EGL error 0x${EGL14.eglGetError().toString(16)})"

  companion object {
    private const val EGL_OPENGL_ES3_BIT = 0x00000040
    private const val EGL_RECORDABLE_ANDROID = 0x3142
    private const val IMAGE_READER_BUFFERS = 3
    private const val PIXEL_COPY_TIMEOUT_SECONDS = 5L

    fun create(): AndroidEglTestRenderDriver {
      val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
      check(display != EGL14.EGL_NO_DISPLAY) { "Android test EGL display is unavailable" }
      val pixelCopyThread = HandlerThread("android-ffi-test-pixel-copy").apply { start() }
      try {
        val version = IntArray(2)
        eglCheck(display, EGL14.eglInitialize(display, version, 0, version, 1), "initialize EGL")
        eglCheck(display, EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API), "bind the OpenGL ES API")
        return AndroidEglTestRenderDriver(display, chooseConfig(display), pixelCopyThread)
      } catch (error: Throwable) {
        pixelCopyThread.quitSafely()
        EGL14.eglTerminate(display)
        throw error
      }
    }

    private fun createImageReader(width: Int, height: Int): ImageReader {
      return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ImageReader.newInstance(
          width,
          height,
          PixelFormat.RGBA_8888,
          IMAGE_READER_BUFFERS,
          HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_CPU_READ_OFTEN,
        )
      } else {
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, IMAGE_READER_BUFFERS)
      }
    }

    private fun chooseConfig(display: EGLDisplay): EGLConfig {
      return chooseConfig(display, recordable = true)
        ?: chooseConfig(display, recordable = false)
        ?: error("No GLES 3 window config is available")
    }

    private fun chooseConfig(display: EGLDisplay, recordable: Boolean): EGLConfig? {
      val attributes = ArrayList<Int>(20)
      attributes += EGL14.EGL_RENDERABLE_TYPE
      attributes += EGL_OPENGL_ES3_BIT
      attributes += EGL14.EGL_SURFACE_TYPE
      attributes += EGL14.EGL_WINDOW_BIT
      attributes += EGL14.EGL_RED_SIZE
      attributes += 8
      attributes += EGL14.EGL_GREEN_SIZE
      attributes += 8
      attributes += EGL14.EGL_BLUE_SIZE
      attributes += 8
      attributes += EGL14.EGL_ALPHA_SIZE
      attributes += 8
      attributes += EGL14.EGL_DEPTH_SIZE
      attributes += 24
      attributes += EGL14.EGL_STENCIL_SIZE
      attributes += 8
      if (recordable) {
        attributes += EGL_RECORDABLE_ANDROID
        attributes += EGL14.EGL_TRUE
      }
      attributes += EGL14.EGL_NONE
      val configs = arrayOfNulls<EGLConfig>(1)
      val count = IntArray(1)
      if (
        !EGL14.eglChooseConfig(
          display,
          attributes.toIntArray(),
          0,
          configs,
          0,
          configs.size,
          count,
          0,
        )
      ) {
        return null
      }
      if (count[0] <= 0 || configs[0] == null) return null
      return configs[0]
    }

    private fun eglCheck(display: EGLDisplay, success: Boolean, operation: String) {
      check(success) { eglFailure(display, operation) }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun eglFailure(display: EGLDisplay, operation: String): String =
      "Failed to $operation (EGL error 0x${EGL14.eglGetError().toString(16)})"
  }
}
