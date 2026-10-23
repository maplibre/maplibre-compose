package org.maplibre.compose.map

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLDisplay
import org.maplibre.compose.mlnffi.AndroidEglDisplay
import org.maplibre.compose.mlnffi.AndroidVulkanOffscreenContext
import org.maplibre.compose.mlnffi.EglContextHandles
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.NativeHandle
import org.maplibre.nativeffi.generated.MapHandle
import org.maplibre.nativeffi.generated.OpenglClientApi
import org.maplibre.nativeffi.generated.OpenglContextOwnership
import org.maplibre.nativeffi.generated.OpenglOwnedTextureDescriptor
import org.maplibre.nativeffi.generated.RenderSessionAttachOptions
import org.maplibre.nativeffi.generated.RenderSessionAttachment
import org.maplibre.nativeffi.generated.VulkanOwnedTextureDescriptor

internal actual class NativeSnapshotRenderTarget
private constructor(private val delegate: Delegate) : AutoCloseable {
  actual fun attach(
    map: MapHandle,
    extent: MapExtent,
    options: RenderSessionAttachOptions,
  ): RenderSessionAttachment = delegate.attach(map, extent, options)

  actual override fun close() = delegate.close()

  actual companion object {
    actual fun select(backends: Set<MapRenderBackend>): NativeSnapshotRenderTargetPlan? =
      when {
        MapRenderBackend.Vulkan in backends ->
          NativeSnapshotRenderTargetPlan {
            NativeSnapshotRenderTarget(VulkanDelegate(AndroidVulkanOffscreenContext.create()))
          }
        MapRenderBackend.OpenGl in backends ->
          NativeSnapshotRenderTargetPlan { NativeSnapshotRenderTarget(OpenGlDelegate.create()) }
        else -> null
      }
  }

  private interface Delegate : AutoCloseable {
    fun attach(
      map: MapHandle,
      extent: MapExtent,
      options: RenderSessionAttachOptions,
    ): RenderSessionAttachment
  }

  private class VulkanDelegate(private val context: AndroidVulkanOffscreenContext) : Delegate {
    override fun attach(
      map: MapHandle,
      extent: MapExtent,
      options: RenderSessionAttachOptions,
    ): RenderSessionAttachment =
      map.attachVulkanOwnedTexture(
        VulkanOwnedTextureDescriptor(extent.toFfiExtent(), context.handles.toFfi()),
        options,
      )

    override fun close() = context.close()
  }

  /**
   * The EGL display and config of a private snapshot context. The session creates that context on
   * its own core worker, so this holds no context of its own.
   */
  private class OpenGlDelegate
  private constructor(private var display: EGLDisplay?, private val config: EGLConfig) : Delegate {
    override fun attach(
      map: MapHandle,
      extent: MapExtent,
      options: RenderSessionAttachOptions,
    ): RenderSessionAttachment {
      val currentDisplay = checkNotNull(display) { "The snapshot render target is closed" }
      val context =
        EglContextHandles(
          display = NativeHandle(currentDisplay.nativeHandle),
          config = NativeHandle(config.nativeHandle),
          shareContext = NativeHandle(0L),
          getProcAddress = NativeHandle(0L),
          ownership = OpenglContextOwnership.DEDICATED,
          clientApi = OpenglClientApi.GLES,
        )
      return map.attachOpenglOwnedTexture(
        OpenglOwnedTextureDescriptor(extent.toFfiExtent(), context.toFfi()),
        options,
      )
    }

    override fun close() {
      display = null
    }

    companion object {
      private const val EGL_OPENGL_ES3_BIT = 0x00000040

      fun create(): OpenGlDelegate {
        val display = AndroidEglDisplay.default
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
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)) {
          "Could not choose an offscreen EGL config"
        }
        val config = checkNotNull(configs[0]) { "No EGL config supports OpenGL ES 3 snapshots" }
        return OpenGlDelegate(display, config)
      }
    }
  }
}
