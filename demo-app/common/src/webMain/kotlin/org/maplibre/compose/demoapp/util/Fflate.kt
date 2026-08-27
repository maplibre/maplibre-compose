@file:JsModule("fflate")

package org.maplibre.compose.demoapp.util

import js.buffer.ArrayBuffer
import js.typedarrays.Uint8Array
import kotlin.js.JsAny

internal external interface ZipEntries : JsAny

internal external interface FlateError : JsAny {
  val message: String?
}

internal external fun unzip(
  data: Uint8Array<ArrayBuffer>,
  callback: (FlateError?, ZipEntries?) -> Unit,
): () -> Unit
