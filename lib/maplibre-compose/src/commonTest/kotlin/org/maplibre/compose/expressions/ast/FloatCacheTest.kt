package org.maplibre.compose.expressions.ast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class FloatCacheTest {
  @Test
  fun testSmallIntsAreCached() {
    var initCalls = 0
    val cache = FloatCache { float ->
      initCalls++
      Any()
    }
    // During initialization, SIZE (512) ints and SIZE (512) floats are created
    val initialCalls = initCalls
    assertEquals(FloatCache.SIZE * 2, initialCalls)

    for (i in 0 until FloatCache.SIZE) {
      val first = cache[i.toFloat()]
      val second = cache[i.toFloat()]
      assertSame(first, second)
    }
    // No additional init calls should have occurred
    assertEquals(initialCalls, initCalls)
  }

  @Test
  fun testSmallFloatsAreCached() {
    val cache = FloatCache { float -> Any() }

    // Verify all 512 multiples of RESOLUTION hit the cache and return identical instances
    for (i in 0 until FloatCache.SIZE) {
      val floatValue = i.toFloat() * FloatCache.RESOLUTION
      val first = cache[floatValue]
      val second = cache[floatValue]
      assertSame(first, second, "Failed caching for index $i (float=$floatValue)")
    }
  }

  @Test
  fun testSpecificPrecisionSensitiveFloats() {
    val cache = FloatCache { float -> Any() }

    // Indices known to fail with naive float division due to precision drift
    val testIndices = listOf(13, 21, 26, 31, 42, 52, 63, 73, 84, 94, 105)
    for (idx in testIndices) {
      val floatValue = idx.toFloat() * FloatCache.RESOLUTION
      val first = cache[floatValue]
      val second = cache[floatValue]
      assertSame(
        first,
        second,
        "Failed caching for known precision-sensitive index $idx (float=$floatValue)",
      )
    }
  }

  @Test
  fun testNonMultiplesCallInit() {
    var extraCalls = 0
    val cache = FloatCache { float ->
      extraCalls++
      Any()
    }
    val initialCalls = extraCalls

    val nonMultiples =
      listOf(0.66f, 0.01f, 1.234f, -0.05f, 600f, Float.NaN, Float.POSITIVE_INFINITY)
    for (f in nonMultiples) {
      val before = extraCalls
      cache[f]
      assertEquals(before + 1, extraCalls, "Expected fresh init for non-multiple $f")
    }
  }
}
