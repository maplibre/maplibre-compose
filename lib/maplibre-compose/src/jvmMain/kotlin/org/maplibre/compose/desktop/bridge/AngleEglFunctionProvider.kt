package org.maplibre.compose.desktop.bridge

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import org.lwjgl.system.FunctionProvider

/**
 * Resolves OpenGL ES entry points the same way Nucleus/Skia does on Windows:
 * `eglGetProcAddress` first, then `libGLESv2` for core 2.0 symbols that some
 * ANGLE builds do not publish through EGL.
 *
 * The host must have made that EGL context current before any GL call.
 */
internal object AngleEglFunctionProvider : FunctionProvider {
  private val linker = Linker.nativeLinker()
  private val eglGetProcAddress =
    linker.downcallHandle(
      findEglGetProcAddress(),
      FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )
  private val glesLookup =
    runCatching { SymbolLookup.libraryLookup("libGLESv2", Arena.global()) }.getOrNull()
      ?: runCatching { SymbolLookup.libraryLookup("GLESv2", Arena.global()) }.getOrNull()

  override fun getFunctionAddress(functionName: ByteBuffer): Long {
    val remaining = functionName.remaining()
    if (remaining <= 0) return 0L
    val bytes = ByteArray(remaining)
    val view = functionName.duplicate()
    view.get(bytes)
    var end = bytes.size
    while (end > 0 && bytes[end - 1] == 0.toByte()) end -= 1
    return address(String(bytes, 0, end, Charsets.US_ASCII))
  }

  fun address(name: String): Long {
    Arena.ofConfined().use { arena ->
      val cName = arena.allocateFrom(name)
      val fromEgl = (eglGetProcAddress.invokeExact(cName) as MemorySegment).address()
      if (fromEgl != 0L) return fromEgl
      return glesLookup?.find(name)?.map { it.address() }?.orElse(0L) ?: 0L
    }
  }

  private fun findEglGetProcAddress(): MemorySegment {
    val loaded = SymbolLookup.loaderLookup().find("eglGetProcAddress")
    if (loaded.isPresent) return loaded.get()
    for (library in listOf("libEGL", "EGL")) {
      val found =
        runCatching { SymbolLookup.libraryLookup(library, Arena.global()).find("eglGetProcAddress") }
          .getOrNull()
      if (found != null && found.isPresent) return found.get()
    }
    error(
      "eglGetProcAddress is not in this process. Load ANGLE's libEGL before " +
        "creating a MapLibre OpenGL host on Windows."
    )
  }
}
