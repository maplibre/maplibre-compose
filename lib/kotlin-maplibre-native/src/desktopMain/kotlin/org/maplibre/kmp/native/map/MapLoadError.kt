package org.maplibre.kmp.native.map

import smjni.jnigen.CalledByNative
import smjni.jnigen.ExposeToNative

@ExposeToNative
public enum class MapLoadError(@CalledByNative public val nativeValue: Int) {
  STYLE_PARSE_ERROR(0),
  STYLE_LOAD_ERROR(1),
  NOT_FOUND_ERROR(2),
  UNKNOWN_ERROR(3);

  internal companion object {
    @JvmStatic
    @CalledByNative
    fun fromNativeValue(value: Int): MapLoadError {
      return MapLoadError.entries.find { it.nativeValue == value } ?: error("Unknown value: $value")
    }
  }
}
