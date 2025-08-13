package org.maplibre.kmp.native.util

import java.lang.ref.Cleaner
import smjni.jnigen.CalledByNative
import smjni.jnigen.ExposeToNative

@ExposeToNative
internal class AutoCleanPointer internal constructor(new: () -> Long, destroy: (Long) -> Unit) {
  @get:CalledByNative val rawPtr: Long = new()

  init {
    cleaner.register(this) { destroy(rawPtr) }
  }

  private companion object Companion {
    val cleaner: Cleaner = Cleaner.create()

    init {
      SharedLibraryLoader.load()
    }
  }
}
