package org.maplibre.compose.mlnffi

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.pthread_getname_np
import platform.posix.pthread_self

private const val MaxThreadNameLength = 63

internal actual fun currentMlnFfiThreadName(): String = memScoped {
  val name = allocArray<ByteVar>(MaxThreadNameLength + 1)
  if (pthread_getname_np(pthread_self(), name, (MaxThreadNameLength + 1).convert()) != 0) {
    return@memScoped ""
  }
  name.toKString()
}
