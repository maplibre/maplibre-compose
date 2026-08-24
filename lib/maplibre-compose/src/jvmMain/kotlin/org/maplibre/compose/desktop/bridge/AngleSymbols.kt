package org.maplibre.compose.desktop.bridge

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout

/**
 * Resolves OpenGL ES entry points through `eglGetProcAddress`, then ANGLE's GLES library.
 *
 * The host makes its EGL context current before calling a resolved function.
 */
internal object AngleSymbols {
  private val linker = Linker.nativeLinker()
  private val eglGetProcAddress =
    linker.downcallHandle(
      findEglGetProcAddress(),
      FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )
  private val glesLookup =
    runCatching { SymbolLookup.libraryLookup("libGLESv2", Arena.global()) }.getOrNull()
      ?: runCatching { SymbolLookup.libraryLookup("GLESv2", Arena.global()) }.getOrNull()

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
      val found = runCatching {
        SymbolLookup.libraryLookup(library, Arena.global()).find("eglGetProcAddress")
      }
        .getOrNull()
      if (found != null && found.isPresent) return found.get()
    }
    error(
      "eglGetProcAddress is not in this process. Load ANGLE's libEGL before " +
        "creating a MapLibre OpenGL host on Windows."
    )
  }
}
