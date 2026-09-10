package org.maplibre.compose.resource

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import org.maplibre.compose.util.rethrowIfFatal
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL

/**
 * Reads a resource through `NSURL`; in practice the resource provider passes only `file:` URLs
 * here, which include the application bundle's packaged resources. Every failure arrives as an
 * [MlnFfiResourceReadException].
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun readPlatformResourceBytes(url: String): ByteArray =
  try {
    val nsUrl =
      NSURL.URLWithString(url)
        ?: throw MlnFfiResourceReadException(
          MlnFfiResourceReadFailure.INVALID_URL,
          IllegalArgumentException("NSURL rejected '$url'"),
        )
    if (nsUrl.scheme == "file") {
      val path = nsUrl.path
      if (path == null || !NSFileManager.defaultManager.fileExistsAtPath(path)) {
        throw MlnFfiResourceReadException(
          MlnFfiResourceReadFailure.NOT_FOUND,
          NoSuchFileException(path ?: url),
        )
      }
    }
    val data =
      NSData.dataWithContentsOfURL(nsUrl)
        ?: throw MlnFfiResourceReadException(
          MlnFfiResourceReadFailure.UNREADABLE,
          IllegalStateException("NSData could not read '$url'"),
        )
    data.toByteArray()
  } catch (error: MlnFfiResourceReadException) {
    throw error
  } catch (error: Throwable) {
    rethrowIfFatal(error)
    throw MlnFfiResourceReadException(MlnFfiResourceReadFailure.UNREADABLE, error)
  }

private class NoSuchFileException(path: String) : Exception("No such file: $path")

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
  val size = length.toInt()
  if (size == 0) return ByteArray(0)
  return checkNotNull(bytes) { "NSData of $size bytes reports no buffer" }
    .reinterpret<ByteVar>()
    .readBytes(size)
}
