package org.maplibre.compose.demoapp.util

import js.buffer.ArrayBuffer
import js.typedarrays.Uint8Array
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

internal actual suspend fun unzip(bytes: ByteArray): Map<String, ByteArray> =
  suspendCancellableCoroutine { continuation ->
    val input = Uint8Array<ArrayBuffer>(bytes.size)
    input.asDynamic().set(bytes)
    val terminate =
      unzip(input) { error, entries ->
        if (continuation.isActive) {
          if (error != null) {
            continuation.resumeWithException(Exception(error.message ?: "ZIP extraction failed"))
          } else {
            continuation.resume(entries!!.toByteArrayMap())
          }
        }
      }
    continuation.invokeOnCancellation { terminate() }
  }

private fun ZipEntries.toByteArrayMap(): Map<String, ByteArray> {
  val names = js("Object").keys(this).unsafeCast<Array<String>>()
  return names.associateWith { name ->
    val data = asDynamic()[name].unsafeCast<Uint8Array<ArrayBuffer>>()
    ByteArray(data.length).also { it.asDynamic().set(data) }
  }
}
