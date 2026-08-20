@file:OptIn(ExperimentalForeignApi::class)

package org.maplibre.compose.mlnffi

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.files.Path
import org.maplibre.nativeffi.Maplibre
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

internal actual object FfiTestPlatform {
  // The Kotlin/Native binding exposes the custom geometry source callbacks in full.
  actual val runtimeCapabilities = FfiTestRuntimeCapabilities(customGeometrySourceCallbacks = true)

  actual fun initialize() {
    // The runtime klib links MapLibre Native statically into the test binary, so loading only
    // checks that the linked archive's C ABI matches the binding.
    Maplibre.loadNativeLibrary()
  }

  actual fun createCacheFile(): Path {
    initialize()
    val directory = "${NSTemporaryDirectory()}ffi-test-${NSUUID().UUIDString()}"
    check(
      NSFileManager.defaultManager.createDirectoryAtPath(
        directory,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
      )
    ) {
      "Could not create FFI test directory $directory"
    }
    return Path(directory, "cache.db")
  }

  actual fun deleteCacheFile(file: Path) {
    val directory = file.parent ?: return
    NSFileManager.defaultManager.removeItemAtPath(directory.toString(), error = null)
  }

  actual fun createRenderDriver(): FfiTestRenderDriver {
    initialize()
    return IosMetalTestRenderDriver.create()
  }

  /**
   * kotlin.test on Native has no assumption API to record a skip with, and iOS reports every
   * capability the shared suite gates on, so this is unreachable in practice. Failing loudly keeps
   * a test that gains a capability gate from reading as a pass on iOS without ever having run.
   */
  actual fun skip(reason: String): Nothing {
    error(
      "iOS cannot skip a shared FFI test; re-check FfiTestPlatform.runtimeCapabilities: $reason"
    )
  }
}
