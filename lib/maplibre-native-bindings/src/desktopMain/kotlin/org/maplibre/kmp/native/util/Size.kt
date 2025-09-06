package org.maplibre.kmp.native.util

import smjni.jnigen.CalledByNative
import smjni.jnigen.ExposeToNative

@ExposeToNative
public data class Size
@CalledByNative
public constructor(@get:CalledByNative val width: Int, @get:CalledByNative val height: Int) {
  val area: Int
    get() = width * height

  val aspectRatio: Float
    get() = if (height != 0) width.toFloat() / height else 0f

  val isEmpty: Boolean
    get() = width == 0 || height == 0

  public operator fun plus(other: Size): Size {
    return Size(width + other.width, height + other.height)
  }

  public operator fun minus(other: Size): Size {
    return Size(width - other.width, height - other.height)
  }

  public operator fun times(factor: Float): Size {
    return Size((width * factor).toInt(), (height * factor).toInt())
  }

  public operator fun div(factor: Float): Size {
    return Size((width / factor).toInt(), (height / factor).toInt())
  }
}
