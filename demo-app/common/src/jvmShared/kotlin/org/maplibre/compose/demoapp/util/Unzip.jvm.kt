package org.maplibre.compose.demoapp.util

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

internal actual fun unzip(bytes: ByteArray): Map<String, ByteArray> {
  val result = mutableMapOf<String, ByteArray>()
  ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
    var entry = zip.nextEntry
    while (entry != null) {
      if (!entry.isDirectory) result[entry.name] = zip.readBytes()
      entry = zip.nextEntry
    }
  }
  return result
}
