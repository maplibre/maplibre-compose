package org.maplibre.kmp.native.camera

import smjni.jnigen.CalledByNative
import smjni.jnigen.ExposeToNative

@ExposeToNative
public enum class CameraChangeMode(@get:CalledByNative public val nativeValue: Int) {
  IMMEDIATE(0),
  ANIMATED(1);

  internal companion object {
    @JvmStatic
    @CalledByNative
    fun fromNativeValue(value: Int): CameraChangeMode {
      return CameraChangeMode.entries.find { it.nativeValue == value }
        ?: error("Unknown value: $value")
    }
  }
}
