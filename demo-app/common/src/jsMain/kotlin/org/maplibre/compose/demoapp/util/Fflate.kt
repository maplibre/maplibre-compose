@file:JsModule("fflate")

package org.maplibre.compose.demoapp.util

import js.buffer.ArrayBuffer
import js.typedarrays.Uint8Array

internal external interface ZipEntries

internal external interface FlateError {
  val message: String?
}

internal external fun unzip(
  data: Uint8Array<ArrayBuffer>,
  callback: (FlateError?, ZipEntries?) -> Unit,
): () -> Unit
