package org.maplibre.kmp.native.util

import smjni.jnigen.CalledByNative
import smjni.jnigen.ExposeToNative

@ExposeToNative
public data class ScreenCoordinate
@CalledByNative
public constructor(@get:CalledByNative val x: Double, @get:CalledByNative val y: Double) {
  override fun toString(): String = "ScreenCoordinate($x, $y)"
}
