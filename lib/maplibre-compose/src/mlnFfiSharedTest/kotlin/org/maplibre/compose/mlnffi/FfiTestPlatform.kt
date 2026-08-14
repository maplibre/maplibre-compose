package org.maplibre.compose.mlnffi

import kotlinx.io.files.Path
import org.maplibre.compose.testing.RgbaPixel

/** Platform services required by otherwise shared MapLibre Native FFI tests. */
internal expect object FfiTestPlatform {
  /** Initializes the packaged native runtime and any process-wide platform services. */
  fun initialize()

  /** A writable, test-unique cache database path. */
  fun createCacheFile(): Path

  /** Removes the cache path and any platform-owned directory containing it. */
  fun deleteCacheFile(file: Path)

  /** Creates the render driver for the native runtime packaged into this test process. */
  fun createRenderDriver(): FfiTestRenderDriver
}

/**
 * The `file:` URL naming [path].
 *
 * A Windows drive letter and backslash separators become a URI path, and reserved characters are
 * percent-encoded.
 */
internal expect fun fileUrlOf(path: Path): String

/** The path [url] names. Inverse of [fileUrlOf], so that a test can check the round trip. */
internal expect fun pathOfFileUrl(url: String): Path

/**
 * Platform/backend mechanics underneath the shared real-map fixture.
 *
 * One test process contains one native runtime; a loaded MapLibre Native library cannot be replaced
 * in-process, so CI supplies another process or APK for every other applicable runtime.
 */
internal interface FfiTestRenderDriver : MlnFfiMapHost {
  /** Presents one completed producer target through the platform's production bridge. */
  fun present(target: MlnFfiRenderTarget): Boolean

  /** Reads one pixel after presentation. */
  fun readPixel(x: Int, y: Int): RgbaPixel
}
