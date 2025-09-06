package org.maplibre.kmp.native.util

import smjni.jnigen.CalledByNative
import smjni.jnigen.ExposeToNative

@ExposeToNative
public data class EdgeInsets
@CalledByNative
public constructor(
  @get:CalledByNative val top: Double = 0.0,
  @get:CalledByNative val left: Double = 0.0,
  @get:CalledByNative val bottom: Double = 0.0,
  @get:CalledByNative val right: Double = 0.0,
) {
  init {
    require(!top.isNaN()) { "top must not be NaN" }
    require(!left.isNaN()) { "left must not be NaN" }
    require(!bottom.isNaN()) { "bottom must not be NaN" }
    require(!right.isNaN()) { "right must not be NaN" }
  }

  val isFlush: Boolean
    get() = top == 0.0 && left == 0.0 && bottom == 0.0 && right == 0.0

  public operator fun plus(other: EdgeInsets): EdgeInsets =
    EdgeInsets(top + other.top, left + other.left, bottom + other.bottom, right + other.right)

  public fun getCenter(width: Int, height: Int): ScreenCoordinate {
    val centerX = (width - left - right) / 2.0 + left
    val centerY = (height - top - bottom) / 2.0 + top
    return ScreenCoordinate(centerX, centerY)
  }
}
