package org.maplibre.kmp.native.map

import smjni.jnigen.CalledByNative
import smjni.jnigen.ExposeToNative

@ExposeToNative
public enum class MapMode(@get:CalledByNative public val nativeValue: Int) {
  CONTINUOUS(0),
  STATIC(1),
  TILE(2);

  internal companion object {
    @JvmStatic
    @CalledByNative
    fun fromNativeValue(value: Int): MapMode {
      return entries.find { it.nativeValue == value } ?: error("Unknown value: $value")
    }
  }
}
