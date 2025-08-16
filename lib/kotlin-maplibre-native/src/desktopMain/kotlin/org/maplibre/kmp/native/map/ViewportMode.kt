package org.maplibre.kmp.native.map

import smjni.jnigen.CalledByNative
import smjni.jnigen.ExposeToNative

@ExposeToNative
public enum class ViewportMode(@get:CalledByNative public val nativeValue: Int) {
  DEFAULT(0),
  FLIPPED_Y(1);

  internal companion object {
    @JvmStatic
    @CalledByNative
    fun fromNativeValue(value: Int): ViewportMode {
      return entries.find { it.nativeValue == value } ?: error("Unknown value: $value")
    }
  }
}
