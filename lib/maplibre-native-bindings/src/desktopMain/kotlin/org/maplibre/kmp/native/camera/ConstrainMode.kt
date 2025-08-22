package org.maplibre.kmp.native.map

import smjni.jnigen.CalledByNative
import smjni.jnigen.ExposeToNative

@ExposeToNative
public enum class ConstrainMode(@get:CalledByNative public val nativeValue: Int) {
  NONE(0),
  HEIGHT_ONLY(1),
  WIDTH_AND_HEIGHT(2),
  SCREEN(3);

  internal companion object {
    @JvmStatic
    @CalledByNative
    fun fromNativeValue(value: Int): ConstrainMode {
      return entries.find { it.nativeValue == value } ?: error("Unknown value: $value")
    }
  }
}
