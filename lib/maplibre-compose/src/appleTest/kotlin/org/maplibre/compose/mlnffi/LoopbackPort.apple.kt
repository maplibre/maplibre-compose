@file:OptIn(ExperimentalForeignApi::class)

package org.maplibre.compose.mlnffi

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.bind
import platform.posix.close
import platform.posix.errno
import platform.posix.getsockname
import platform.posix.memset
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar

/** Binds port 0 on the loopback interface, returning the port the system chose. */
internal actual fun unusedLoopbackPort(): Int = memScoped {
  val descriptor = socket(AF_INET, SOCK_STREAM, 0)
  check(descriptor >= 0) { "Could not open a loopback socket (errno $errno)" }
  try {
    val address = alloc<sockaddr_in>()
    memset(address.ptr, 0, sizeOf<sockaddr_in>().convert())
    // Darwin's bind reads the length field, and 127.0.0.1 keeps the port off every real interface.
    // Darwin is little-endian, so the network-order fields below swap their bytes.
    address.sin_len = sizeOf<sockaddr_in>().convert()
    address.sin_family = AF_INET.convert()
    address.sin_addr.s_addr = 0x0100007Fu
    address.sin_port = 0u
    check(
      bind(descriptor, address.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) == 0
    ) {
      "Could not bind a loopback port (errno $errno)"
    }
    val bound = alloc<sockaddr_in>()
    val length = alloc<socklen_tVar>()
    length.value = sizeOf<sockaddr_in>().convert()
    check(getsockname(descriptor, bound.ptr.reinterpret<sockaddr>(), length.ptr) == 0) {
      "Could not read back the bound loopback port (errno $errno)"
    }
    val networkOrderPort = bound.sin_port.toUInt()
    ((networkOrderPort and 0xFFu) shl 8 or (networkOrderPort shr 8)).toInt()
  } finally {
    close(descriptor)
  }
}
