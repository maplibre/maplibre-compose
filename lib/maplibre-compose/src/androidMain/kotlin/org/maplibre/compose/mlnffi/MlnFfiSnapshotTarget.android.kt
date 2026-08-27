package org.maplibre.compose.mlnffi

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLDisplay
import org.maplibre.nativeffi.Maplibre
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.render.EglContextDescriptor
import org.maplibre.nativeffi.render.NativePointer
import org.maplibre.nativeffi.render.OpenGLClientApi
import org.maplibre.nativeffi.render.OpenGLContextOwnership
import org.maplibre.nativeffi.render.OpenGLOwnedTextureDescriptor
import org.maplibre.nativeffi.render.RenderBackend
import org.maplibre.nativeffi.render.RenderSessionHandle
import org.maplibre.nativeffi.render.RenderTargetExtent

internal actual fun createSnapshotTarget(): MlnFfiSnapshotTarget {
  val backends = Maplibre.supportedRenderBackends()
  if (RenderBackend.OPENGL !in backends) {
    throw UnsupportedOperationException(
      "MapState.captureStillImage has no still-image path for the packaged Android runtime " +
        "(${backends.joinToString().ifEmpty { "none" }}); package the OpenGL runtime"
    )
  }
  return AndroidEglSnapshotTarget()
}

/**
 * An EGL display and config for a dedicated OpenGL ES still-image session. The session creates the
 * snapshot thread's context, so this object holds none of its own.
 */
private class AndroidEglSnapshotTarget : MlnFfiSnapshotTarget {
  override val backend: MapRenderBackend = MapRenderBackend.OPENGL

  private val display: EGLDisplay
  private val config: EGLConfig

  init {
    val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    check(display != EGL14.EGL_NO_DISPLAY) { "EGL display is unavailable" }
    try {
      val version = IntArray(2)
      eglCheck(EGL14.eglInitialize(display, version, 0, version, 1), "initialize EGL")
      eglCheck(EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API), "bind the OpenGL ES API")
      this.display = display
      this.config = chooseConfig(display)
    } catch (error: Throwable) {
      EGL14.eglTerminate(display)
      throw error
    }
  }

  override fun attach(map: MapHandle, extent: RenderTargetExtent): RenderSessionHandle =
    map.attachOpenGLOwnedTexture(
      OpenGLOwnedTextureDescriptor(
        extent = extent,
        context =
          EglContextDescriptor(
            display = NativePointer.ofAddress(display.nativeHandle),
            config = NativePointer.ofAddress(config.nativeHandle),
            shareContext = NativePointer.NULL_POINTER,
            // Android's EGL dispatch is supplied by the runtime; no host proc loader is needed.
            getProcAddress = NativePointer.NULL_POINTER,
            clientApi = OpenGLClientApi.GLES,
            ownership = OpenGLContextOwnership.DEDICATED,
          ),
      )
    )

  override fun close() {
    EGL14.eglTerminate(display)
    EGL14.eglReleaseThread()
  }

  private companion object {
    private const val EGL_OPENGL_ES3_BIT = 0x00000040

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
      check(success) {
        "Failed to $operation (EGL error 0x${EGL14.eglGetError().toString(16)})"
      }
    }
  }
}
