package org.maplibre.compose.demoapp.util

import js.buffer.ArrayBuffer
import js.typedarrays.Uint8Array
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsString
import kotlinx.coroutines.suspendCancellableCoroutine

internal actual suspend fun unzip(bytes: ByteArray): Map<String, ByteArray> =
  suspendCancellableCoroutine { continuation ->
    val input = Uint8Array<ArrayBuffer>(bytes.size)
    bytes.forEachIndexed { index, byte -> setUint8At(input, index, byte.toInt() and 0xFF) }
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
  val names = objectKeys(this).toList().map(::jsStringToKotlin)
  return names.associateWith { name ->
    val data = jsUnsafeCast<Uint8Array<ArrayBuffer>>(jsGet(this, name))
    ByteArray(data.length) { index -> getUint8At(data, index).toByte() }
  }
}

private fun objectKeys(value: JsAny): JsArray<JsString> = js("Object.keys(value)")

private fun jsStringToKotlin(value: JsString): String = js("value")

private fun jsGet(value: JsAny, name: String): JsAny? = js("value[name]")

private fun <T : JsAny?> jsUnsafeCast(value: JsAny?): T = js("value")

private fun setUint8At(target: Uint8Array<*>, index: Int, value: Int): Unit =
  js("{ target[index] = value }")

private fun getUint8At(target: Uint8Array<*>, index: Int): Int = js("target[index]")
