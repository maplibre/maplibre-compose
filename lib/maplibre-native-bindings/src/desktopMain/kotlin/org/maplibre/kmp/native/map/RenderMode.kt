package org.maplibre.kmp.native.map

import smjni.jnigen.CalledByNative
import smjni.jnigen.ExposeToNative

@ExposeToNative
public enum class RenderMode(@CalledByNative public val nativeValue: Int) {
  PARTIAL(0),
  FULL(1);

  internal companion object {
    @JvmStatic
    @CalledByNative
    fun fromNativeValue(value: Int): RenderMode {
      return RenderMode.entries.find { it.nativeValue == value } ?: error("Unknown value: $value")
    }
  }
}
