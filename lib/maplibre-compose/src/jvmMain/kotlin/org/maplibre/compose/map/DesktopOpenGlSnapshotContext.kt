package org.maplibre.compose.map

import org.lwjgl.PointerBuffer
import org.lwjgl.egl.EGL
import org.lwjgl.egl.EGL14
import org.lwjgl.egl.EGL15
import org.lwjgl.system.MemoryStack
import org.maplibre.compose.desktop.bridge.DesktopEglContext
import org.maplibre.compose.desktop.bridge.WindowsGlDrawable
import org.maplibre.nativeffi.render.EglContextDescriptor
import org.maplibre.nativeffi.render.NativePointer
import org.maplibre.nativeffi.render.OpenGLClientApi
import org.maplibre.nativeffi.render.OpenGLContextDescriptor
import org.maplibre.nativeffi.render.OpenGLContextOwnership
import org.maplibre.nativeffi.render.WglContextDescriptor

/** A platform display connection from which the FFI owns a private OpenGL context. */
internal sealed interface DesktopOpenGlSnapshotContext : AutoCloseable {
  val descriptor: OpenGLContextDescriptor

  companion object {
    fun create(os: String): DesktopOpenGlSnapshotContext =
      when {
        os.contains("linux") -> EglSnapshotContext.create()
        os.contains("windows") -> WglSnapshotContext.create()
        os.contains("mac") -> AngleSnapshotContext()
        else -> throw UnsupportedOperationException("No offscreen OpenGL context provider for $os")
      }
  }
}

private class EglSnapshotContext private constructor(display: Long, config: Long) :
  DesktopOpenGlSnapshotContext {
  override val descriptor: OpenGLContextDescriptor =
    EglContextDescriptor(
      display = NativePointer.ofAddress(display),
      config = NativePointer.ofAddress(config),
      shareContext = NativePointer.NULL_POINTER,
      getProcAddress = NativePointer.NULL_POINTER,
      clientApi = OpenGLClientApi.GL,
      ownership = OpenGLContextOwnership.DEDICATED,
    )

  // EGL display connections are process-shared. The FFI owns and destroys this snapshotter's
  // context, while the connection remains initialized for other maps and snapshotters.
  override fun close() = Unit

  companion object {
    private const val EGL_PLATFORM_SURFACELESS_MESA = 0x31DD

    @Suppress("SENSELESS_COMPARISON")
    private val library by lazy {
      if (EGL.getFunctionProvider() == null) EGL.create()
    }

    private val connection: Pair<Long, Long> by lazy {
      MemoryStack.stackPush().use { stack ->
        val display = initializedDisplay(stack)
        try {
          val configs = stack.mallocPointer(1)
          val configCount = stack.mallocInt(1)
          val attributes =
            stack.ints(
              EGL14.EGL_SURFACE_TYPE,
              EGL14.EGL_PBUFFER_BIT,
              EGL14.EGL_RENDERABLE_TYPE,
              EGL14.EGL_OPENGL_BIT,
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
          check(
            EGL14.eglChooseConfig(display, attributes, configs, configCount) && configCount[0] > 0
          ) {
            "No EGL config supports OpenGL pbuffer rendering"
          }
          display to configs[0]
        } catch (error: Throwable) {
          EGL14.eglTerminate(display)
          throw error
        }
      }
    }

    fun create(): EglSnapshotContext {
      library
      check(EGL14.eglBindAPI(EGL14.EGL_OPENGL_API)) { "Could not bind EGL OpenGL" }
      val (display, config) = connection
      return EglSnapshotContext(display, config)
    }

    private fun initializedDisplay(stack: MemoryStack): Long {
      val surfaceless = runCatching {
        EGL15.eglGetPlatformDisplay(
          EGL_PLATFORM_SURFACELESS_MESA,
          EGL14.EGL_DEFAULT_DISPLAY,
          null as PointerBuffer?,
        )
      }
        .getOrDefault(EGL14.EGL_NO_DISPLAY)
      if (surfaceless != EGL14.EGL_NO_DISPLAY && initialize(surfaceless, stack)) {
        return surfaceless
      }
      val fallback = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
      check(fallback != EGL14.EGL_NO_DISPLAY && initialize(fallback, stack)) {
        "No EGL display is available for offscreen snapshots"
      }
      return fallback
    }

    private fun initialize(display: Long, stack: MemoryStack): Boolean {
      val major = stack.mallocInt(1)
      val minor = stack.mallocInt(1)
      if (EGL14.eglInitialize(display, major, minor)) return true
      EGL14.eglTerminate(display)
      return false
    }
  }
}

private class WglSnapshotContext(private val drawable: WindowsGlDrawable) :
  DesktopOpenGlSnapshotContext {
  override val descriptor =
    WglContextDescriptor(
      deviceContext = NativePointer.ofAddress(drawable.deviceContext),
      shareContext = NativePointer.NULL_POINTER,
      getProcAddress = NativePointer.NULL_POINTER,
      ownership = OpenGLContextOwnership.DEDICATED,
    )

  override fun close() = drawable.close()

  companion object {
    fun create() = WglSnapshotContext(WindowsGlDrawable.create())
  }
}

private class AngleSnapshotContext : DesktopOpenGlSnapshotContext {
  private val context = DesktopEglContext.create(metalDevice = 0L)
  override val descriptor: OpenGLContextDescriptor
    get() =
      context.handles.let {
        EglContextDescriptor(
          display = NativePointer.ofAddress(it.display.address),
          config = NativePointer.ofAddress(it.config.address),
          shareContext = NativePointer.ofAddress(it.shareContext.address),
          getProcAddress = NativePointer.ofAddress(it.getProcAddress.address),
          ownership = OpenGLContextOwnership.SHARED,
        )
      }

  override fun close() = context.close()
}
