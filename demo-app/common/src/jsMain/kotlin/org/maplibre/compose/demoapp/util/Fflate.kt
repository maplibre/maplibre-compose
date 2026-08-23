@file:JsModule("fflate")

package org.maplibre.compose.demoapp.util

import js.buffer.ArrayBuffer
import js.typedarrays.Uint8Array

internal external interface ZipEntries

internal external fun unzipSync(data: Uint8Array<ArrayBuffer>): ZipEntries
