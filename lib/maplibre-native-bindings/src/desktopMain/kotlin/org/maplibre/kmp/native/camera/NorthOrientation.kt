package org.maplibre.kmp.native.map

import smjni.jnigen.CalledByNative
import smjni.jnigen.ExposeToNative

@ExposeToNative
public enum class NorthOrientation(@get:CalledByNative public val nativeValue: Int) {
  UPWARDS(0),
  RIGHTWARDS(1),
  DOWNWARDS(2),
  LEFTWARDS(3);

  internal companion object {
    @JvmStatic
    @CalledByNative
    fun fromNativeValue(value: Int): NorthOrientation {
      return entries.find { it.nativeValue == value } ?: error("Unknown value: $value")
    }
  }
}
