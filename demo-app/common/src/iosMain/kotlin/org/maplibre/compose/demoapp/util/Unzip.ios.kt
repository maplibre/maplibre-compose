package org.maplibre.compose.demoapp.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.zlib.ZLIB_VERSION
import platform.zlib.Z_FINISH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream

// Foundation has no ZIP API, so this walks the archive's central directory and
// inflates each entry through the platform's zlib.
internal actual fun unzip(bytes: ByteArray): Map<String, ByteArray> {
  val eocd = findEndOfCentralDirectory(bytes)
  val entryCount = bytes.u16(eocd + 10)
  var offset = bytes.u32(eocd + 16)

  val result = mutableMapOf<String, ByteArray>()
  repeat(entryCount) {
    require(bytes.u32(offset) == 0x02014b50) { "Bad central directory entry" }
    val method = bytes.u16(offset + 10)
    val compressedSize = bytes.u32(offset + 20)
    val uncompressedSize = bytes.u32(offset + 24)
    val nameLength = bytes.u16(offset + 28)
    val extraLength = bytes.u16(offset + 30)
    val commentLength = bytes.u16(offset + 32)
    val localOffset = bytes.u32(offset + 42)
    val name = bytes.decodeToString(offset + 46, offset + 46 + nameLength)

    if (!name.endsWith("/")) {
      val dataStart = localOffset + 30 + bytes.u16(localOffset + 26) + bytes.u16(localOffset + 28)
      val compressed = bytes.copyOfRange(dataStart, dataStart + compressedSize)
      result[name] =
        when (method) {
          0 -> compressed
          8 -> inflateRaw(compressed, uncompressedSize)
          else -> error("Unsupported ZIP compression method $method")
        }
    }

    offset += 46 + nameLength + extraLength + commentLength
  }
  return result
}

private fun findEndOfCentralDirectory(bytes: ByteArray): Int {
  val floor = maxOf(0, bytes.size - 22 - 0xFFFF)
  for (offset in bytes.size - 22 downTo floor) {
    if (bytes.u32(offset) == 0x06054b50) return offset
  }
  error("Not a ZIP archive")
}

private fun ByteArray.u16(offset: Int): Int =
  (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.u32(offset: Int): Int = u16(offset) or (u16(offset + 2) shl 16)

@OptIn(ExperimentalForeignApi::class)
private fun inflateRaw(compressed: ByteArray, uncompressedSize: Int): ByteArray {
  if (uncompressedSize == 0) return ByteArray(0)
  val output = ByteArray(uncompressedSize)
  compressed.usePinned { input ->
    output.usePinned { out ->
      memScoped {
        val stream = alloc<z_stream>()
        stream.next_in = input.addressOf(0).reinterpret()
        stream.avail_in = compressed.size.toUInt()
        stream.next_out = out.addressOf(0).reinterpret()
        stream.avail_out = uncompressedSize.toUInt()
        // -15 selects a raw DEFLATE stream, which is how ZIP stores entries.
        check(inflateInit2_(stream.ptr, -15, ZLIB_VERSION, sizeOf<z_stream>().toInt()) == Z_OK)
        val status = inflate(stream.ptr, Z_FINISH)
        inflateEnd(stream.ptr)
        check(status == Z_STREAM_END) { "ZIP entry failed to inflate: $status" }
      }
    }
  }
  return output
}
