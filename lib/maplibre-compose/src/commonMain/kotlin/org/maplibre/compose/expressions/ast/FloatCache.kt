package org.maplibre.compose.expressions.ast

import kotlin.math.roundToInt

internal class FloatCache<T>(val init: (Float) -> T) {
  private val smallInts = List(Size) { init(it.toFloat()) }
  private val smallFloats = List(Size) { init(it.toFloat() * Resolution) }

  operator fun get(float: Float): T {
    if (float.isNaN()) return init(float)
    val floatIndex = (float / Resolution).roundToInt()
    return when {
      float.isSmallInt() -> smallInts[float.toInt()]
      floatIndex.isSmallInt() && floatIndex.toFloat() * Resolution == float ->
        smallFloats[floatIndex]

      else -> init(float)
    }
  }

  companion object {
    const val Size = 512
    const val Resolution = 0.05f

    internal fun Float.isSmallInt() = toInt().toFloat() == this && toInt().isSmallInt()

    internal fun Int.isSmallInt() = this in 0..<Size
  }
}
