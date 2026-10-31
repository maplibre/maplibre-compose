package org.maplibre.compose.mlnffi

import android.hardware.HardwareBuffer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.view.SurfaceControl
import androidx.annotation.RequiresApi
import org.maplibre.compose.map.MapExtent

/**
 * Map buffers imported as OpenGL ES textures through EGLImages. The session's context joins the
 * share group of a host context that this object keeps current on a pbuffer.
 */
@RequiresApi(33)
internal class AndroidEglMapBuffers
private constructor(
  private val display: EGLDisplay,
  config: EGLConfig,
  private val context: EGLContext,
  private val pbuffer: EGLSurface,
) : AndroidMapBuffers() {
  private val handles =
    EglContextHandles(
      display = NativeHandle(display.nativeHandle),
      config = NativeHandle(config.nativeHandle),
      shareContext = NativeHandle(context.nativeHandle),
      // Android's EGL dispatch is supplied by the runtime; no host proc loader is needed.
      getProcAddress = NativeHandle(0L),
    )

  private val makeCurrent: () -> Unit = {
    if (EGL14.eglGetCurrentContext() != context) {
      eglCheck(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context), "make the context current")
    }
  }

  // OpenGL writes the image's bottom row first; SurfaceFlinger shows the first row at the top.
  override val bufferTransform: Int
    get() = SurfaceControl.BUFFER_TRANSFORM_MIRROR_VERTICAL

  override fun import(
    buffers: List<HardwareBuffer>,
    extent: MapExtent,
    generation: Long,
  ): ImportedRing {
    makeCurrent()
    val textures = IntArray(buffers.size)
    GLES20.glGenTextures(textures.size, textures, 0)
    val images = mutableListOf<Long>()
    try {
      buffers.forEachIndexed { slot, buffer ->
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[slot])
        images += AndroidEglNativeBridge.bindHardwareBuffer(display.nativeHandle, buffer)
      }
      // The session's context reads these bindings from the share group.
      GLES20.glFinish()
    } catch (error: Throwable) {
      release(textures, images)
      throw error
    } finally {
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }
    return object : ImportedRing {
      override val target =
        OpenGlTextureTarget(
          context = handles,
          textureNames = textures.toList(),
          textureTarget = GLES20.GL_TEXTURE_2D,
          format = GLES30.GL_RGBA8,
          origin = TextureOrigin.BottomLeft,
          makeContextCurrent = makeCurrent,
          extent = extent,
          generation = generation,
        )

      override fun close() = release(textures, images)
    }
  }

  private fun release(textures: IntArray, images: List<Long>) {
    makeCurrent()
    GLES20.glDeleteTextures(textures.size, textures, 0)
    images.forEach { AndroidEglNativeBridge.destroyImage(display.nativeHandle, it) }
  }

  override fun closeContext() {
    EGL14.eglMakeCurrent(
      display,
      EGL14.EGL_NO_SURFACE,
      EGL14.EGL_NO_SURFACE,
      EGL14.EGL_NO_CONTEXT,
    )
    EGL14.eglDestroySurface(display, pbuffer)
    EGL14.eglDestroyContext(display, context)
    EGL14.eglReleaseThread()
  }

  companion object {
    private const val EGL_OPENGL_ES3_BIT = 0x00000040

    fun create(): AndroidEglMapBuffers {
      try {
        AndroidEglNativeBridge.load()
      } catch (error: UnsatisfiedLinkError) {
        throw IllegalStateException(
          "Coordinated OpenGL presentation requires the maplibre-compose-runtime-opengl-android " +
            "runtime",
          error,
        )
      }
      val display = AndroidEglDisplay.default
      eglCheck(EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API), "bind the OpenGL ES API")
      val config = chooseConfig(display)
      val context =
        EGL14.eglCreateContext(
          display,
          config,
          EGL14.EGL_NO_CONTEXT,
          intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
          0,
        )
      check(context != EGL14.EGL_NO_CONTEXT) { eglFailure("create the EGL context") }
      val pbuffer =
        EGL14.eglCreatePbufferSurface(
          display,
          config,
          intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
          0,
        )
      if (pbuffer == EGL14.EGL_NO_SURFACE) {
        val failure = eglFailure("create the EGL pbuffer")
        EGL14.eglDestroyContext(display, context)
        error(failure)
      }
      return AndroidEglMapBuffers(display, config, context, pbuffer)
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
        EGL14.eglChooseConfig(display, attributes, 0, configs, 0, configs.size, count, 0),
        "choose an EGL config",
      )
      check(count[0] > 0 && configs[0] != null) {
        "No EGL config supports OpenGL ES 3 pbuffer rendering"
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

/** JNI bindings for the hardware buffer shim in the OpenGL runtime. */
internal object AndroidEglNativeBridge {
  /** Loads the shim, or throws [UnsatisfiedLinkError] without the OpenGL runtime. */
  fun load() {
    System.loadLibrary("maplibre_compose_opengl")
  }

  /**
   * Binds an EGLImage of [buffer] to the texture bound to `GL_TEXTURE_2D` in the current context,
   * and returns the `EGLImageKHR`.
   */
  external fun bindHardwareBuffer(display: Long, buffer: HardwareBuffer): Long

  external fun destroyImage(display: Long, image: Long)
}
