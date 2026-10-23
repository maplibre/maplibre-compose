package org.maplibre.compose.map

import org.maplibre.compose.desktop.bridge.DesktopEglContext
import org.maplibre.compose.mlnffi.EglContextHandles
import org.maplibre.nativeffi.generated.OpenglClientApi
import org.maplibre.nativeffi.generated.OpenglContextDescriptor
import org.maplibre.nativeffi.generated.OpenglContextOwnership

/**
 * The EGL display and config of an FFI OpenGL snapshot texture session. The session creates a
 * private context of its own from them, so it renders and reads back on its native core worker.
 */
internal class DesktopOpenGlSnapshotContext private constructor(metalDevice: Long?) :
  AutoCloseable {
  private val context = DesktopEglContext.create(metalDevice)

  // The private context uses the API that the config was chosen for: GLES through ANGLE on macOS,
  // desktop OpenGL on Linux.
  private val clientApi = if (metalDevice != null) OpenglClientApi.GLES else OpenglClientApi.GL

  val descriptor: OpenglContextDescriptor
    get() =
      context.handles
        .copy(ownership = OpenglContextOwnership.DEDICATED, clientApi = clientApi)
        .let(EglContextHandles::toFfi)

  override fun close() = context.close()

  companion object {
    /**
     * Creates contexts on [os], or null when [os] has no EGL provider. A WGL context offers no
     * private session context, so Windows renders OpenGL snapshots only through Vulkan.
     */
    fun provider(os: String): (() -> DesktopOpenGlSnapshotContext)? =
      when {
        os.contains("linux") -> { ->
          DesktopOpenGlSnapshotContext(null)
        }
        os.contains("mac") -> { ->
          DesktopOpenGlSnapshotContext(0L)
        }
        else -> null
      }
  }
}
