package org.maplibre.compose.map

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import org.maplibre.compose.mlnffi.AndroidEglDisplay
import org.maplibre.compose.mlnffi.AndroidVulkanOffscreenContext
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.VulkanContextHandles
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.render.EglContextDescriptor
import org.maplibre.nativeffi.render.NativePointer
import org.maplibre.nativeffi.render.OpenGLClientApi
import org.maplibre.nativeffi.render.OpenGLContextOwnership
import org.maplibre.nativeffi.render.OpenGLOwnedTextureDescriptor
import org.maplibre.nativeffi.render.RenderSessionHandle
import org.maplibre.nativeffi.render.RenderTargetExtent
import org.maplibre.nativeffi.render.VulkanContextDescriptor
import org.maplibre.nativeffi.render.VulkanOwnedTextureDescriptor

internal actual class NativeSnapshotRenderTarget
private constructor(private val delegate: Delegate) : AutoCloseable {
  actual fun attach(map: MapHandle, extent: MapExtent): RenderSessionHandle =
    delegate.attach(map, extent)

  actual fun <T> withAccess(action: () -> T): T = delegate.withAccess(action)

  actual override fun close() = delegate.close()

  actual companion object {
    actual fun create(backends: Set<MapRenderBackend>): NativeSnapshotRenderTarget {
      val delegate =
        when {
          MapRenderBackend.VULKAN in backends ->
            VulkanDelegate(AndroidVulkanOffscreenContext.create())
          MapRenderBackend.OPENGL in backends -> OpenGlDelegate.create()
          else ->
            throw UnsupportedOperationException(
              "No offscreen Android snapshot backend is available from ${backends.joinToString()}"
            )
        }
      return NativeSnapshotRenderTarget(delegate)
    }
  }

  private interface Delegate : AutoCloseable {
    fun attach(map: MapHandle, extent: MapExtent): RenderSessionHandle

    fun <T> withAccess(action: () -> T): T = action()
  }

  private class VulkanDelegate(private val context: AndroidVulkanOffscreenContext) : Delegate {
    override fun attach(map: MapHandle, extent: MapExtent): RenderSessionHandle =
      map.attachVulkanOwnedTexture(
        VulkanOwnedTextureDescriptor(
          RenderTargetExtent(extent.width, extent.height, extent.scaleFactor),
          context.handles.toDescriptor(),
        )
      )

    override fun close() = context.close()
  }

  private class OpenGlDelegate
  private constructor(
    private var display: EGLDisplay?,
    private val config: EGLConfig,
    private var context: EGLContext,
    private var surface: EGLSurface,
  ) : Delegate {
    override fun attach(map: MapHandle, extent: MapExtent): RenderSessionHandle {
      val currentDisplay = checkNotNull(display) { "The snapshot render target is closed" }
      return map.attachOpenGLOwnedTexture(
        OpenGLOwnedTextureDescriptor(
          RenderTargetExtent(extent.width, extent.height, extent.scaleFactor),
          EglContextDescriptor(
            display = NativePointer.ofAddress(currentDisplay.nativeHandle),
            config = NativePointer.ofAddress(config.nativeHandle),
            shareContext = NativePointer.ofAddress(context.nativeHandle),
            getProcAddress = NativePointer.NULL_POINTER,
            clientApi = OpenGLClientApi.GLES,
            ownership = OpenGLContextOwnership.SHARED,
          ),
        )
      )
    }

    override fun close() {
      display?.let { currentDisplay ->
        EGL14.eglMakeCurrent(
          currentDisplay,
          EGL14.EGL_NO_SURFACE,
          EGL14.EGL_NO_SURFACE,
          EGL14.EGL_NO_CONTEXT,
        )
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(currentDisplay, surface)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(currentDisplay, context)
      }
      display = null
      surface = EGL14.EGL_NO_SURFACE
      context = EGL14.EGL_NO_CONTEXT
      EGL14.eglReleaseThread()
    }

    companion object {
      private const val EGL_OPENGL_ES3_BIT = 0x00000040

      fun create(): OpenGlDelegate {
        val display = AndroidEglDisplay.default
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
          check(EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API)) { "Could not bind OpenGL ES" }
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
          context =
            EGL14.eglCreateContext(
              display,
              config,
              EGL14.EGL_NO_CONTEXT,
              intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
              0,
            )
          check(context != EGL14.EGL_NO_CONTEXT) { "Could not create an offscreen EGL context" }
          surface =
            EGL14.eglCreatePbufferSurface(
              display,
              config,
              intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
              0,
            )
          check(surface != EGL14.EGL_NO_SURFACE) { "Could not create an offscreen EGL surface" }
          check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
            "Could not make the offscreen EGL context current"
          }
          return OpenGlDelegate(
            display = display,
            config = config,
            context = context,
            surface = surface,
          )
        } catch (error: Throwable) {
          EGL14.eglMakeCurrent(
            display,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT,
          )
          if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
          if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
          EGL14.eglReleaseThread()
          throw error
        }
      }
    }
  }
}

private fun VulkanContextHandles.toDescriptor() =
  VulkanContextDescriptor(
    instance = NativePointer.ofAddress(instance.address),
    physicalDevice = NativePointer.ofAddress(physicalDevice.address),
    device = NativePointer.ofAddress(device.address),
    graphicsQueue = NativePointer.ofAddress(graphicsQueue.address),
    graphicsQueueFamilyIndex = graphicsQueueFamilyIndex,
    getInstanceProcAddr = NativePointer.ofAddress(getInstanceProcAddr.address),
    getDeviceProcAddr = NativePointer.ofAddress(getDeviceProcAddr.address),
  )
