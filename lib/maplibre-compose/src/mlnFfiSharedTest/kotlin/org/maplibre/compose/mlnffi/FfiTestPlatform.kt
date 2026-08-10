package org.maplibre.compose.mlnffi

import kotlinx.io.files.Path

/** Platform services required by otherwise shared MapLibre Native FFI tests. */
internal expect object FfiTestPlatform {
  val runtimeCapabilities: FfiTestRuntimeCapabilities

  /** Initializes the packaged native runtime and any process-wide platform services. */
  fun initialize()

  /** A writable, test-unique cache database path. */
  fun createCacheFile(): Path

  /** Removes the cache path and any platform-owned directory containing it. */
  fun deleteCacheFile(file: Path)

  /** Creates the render driver for the native runtime packaged into this test process. */
  fun createRenderDriver(): FfiTestRenderDriver

  /** Records a capability-dependent test as skipped in the platform's test runner. */
  fun skip(reason: String): Nothing
}

/** Feature availability of the packaged FFI runtime/binding pair. */
internal data class FfiTestRuntimeCapabilities(val customGeometrySourceCallbacks: Boolean)

internal data class RgbaPixel(val red: Int, val green: Int, val blue: Int, val alpha: Int)

/**
 * Platform/backend mechanics underneath the shared real-map fixture.
 *
 * One test process contains one native runtime. CI supplies another process or APK for every other
 * applicable runtime, because a loaded MapLibre Native library cannot be replaced in-process.
 */
internal interface FfiTestRenderDriver : MlnFfiMapHost {
  /** Presents one completed producer target through the platform's production bridge. */
  fun present(target: MlnFfiRenderTarget): Boolean

  /** Reads one pixel after presentation; every production bridge test adapter must support this. */
  fun readPixel(x: Int, y: Int): RgbaPixel
}
