package org.maplibre.compose.logging

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import platform.Foundation.NSLog

@OptIn(ExperimentalForeignApi::class)
internal actual fun platformMapLogger(): MapLogger = MapLogger { record ->
  val trace = record.throwable?.let { "\n" + it.stackTraceToString() }.orEmpty()
  val line = "[${record.level}] ${record.toPlatformLine()}$trace"
  memScoped { NSLog("%s", line.cstr.ptr) }
}
