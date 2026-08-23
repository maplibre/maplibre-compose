package org.maplibre.compose.demoapp.util

import js.buffer.ArrayBuffer
import js.typedarrays.Uint8Array

internal actual fun unzip(bytes: ByteArray): Map<String, ByteArray> {
  val input = Uint8Array<ArrayBuffer>(bytes.size)
  input.asDynamic().set(bytes)
  val entries = unzipSync(input)
  val names = js("Object").keys(entries).unsafeCast<Array<String>>()
  return names.associateWith { name ->
    val data = entries.asDynamic()[name].unsafeCast<Uint8Array<ArrayBuffer>>()
    ByteArray(data.length).also { it.asDynamic().set(data) }
  }
}
