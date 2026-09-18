package org.maplibre.compose.map

import org.maplibre.compose.desktop.bridge.DesktopEglContext
import org.maplibre.compose.desktop.bridge.WindowsWglContext
import org.maplibre.nativeffi.render.EglContextDescriptor
import org.maplibre.nativeffi.render.NativePointer
import org.maplibre.nativeffi.render.OpenGLContextDescriptor
import org.maplibre.nativeffi.render.OpenGLContextOwnership
import org.maplibre.nativeffi.render.WglContextDescriptor

/** Owns the share context used by an FFI OpenGL snapshot texture session. */
internal sealed interface DesktopOpenGlSnapshotContext : AutoCloseable {
  val descriptor: OpenGLContextDescriptor

  companion object {
    fun create(os: String): DesktopOpenGlSnapshotContext =
      when {
        os.contains("linux") -> EglSnapshotContext()
        os.contains("windows") -> WglSnapshotContext()
        os.contains("mac") -> EglSnapshotContext(metalDevice = 0L)
        else -> throw UnsupportedOperationException("No offscreen OpenGL context provider for $os")
      }
  }
}

private class WglSnapshotContext : DesktopOpenGlSnapshotContext {
  private val context = WindowsWglContext.create()
  override val descriptor: OpenGLContextDescriptor
    get() =
      context.handles.let {
        WglContextDescriptor(
          deviceContext = NativePointer.ofAddress(it.deviceContext.address),
          shareContext = NativePointer.ofAddress(it.shareContext.address),
          getProcAddress = NativePointer.ofAddress(it.getProcAddress.address),
          ownership = OpenGLContextOwnership.SHARED,
        )
      }

  override fun close() = context.close()
}

private class EglSnapshotContext(metalDevice: Long? = null) : DesktopOpenGlSnapshotContext {
  private val context = DesktopEglContext.create(metalDevice)
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
