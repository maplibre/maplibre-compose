package org.maplibre.compose.expressions.ast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class FloatCacheTest {
  @Test
  fun testSmallIntsAreCached() {
    var initCalls = 0
    val cache = FloatCache {
      initCalls++
      Any()
    }
    val initialCalls = initCalls

    for (i in 0 until FloatCache.SIZE) {
      val first = cache[i.toFloat()]
      val second = cache[i.toFloat()]
      assertSame(first, second)
    }
    assertEquals(initialCalls, initCalls)
  }

  @Test
  fun testSmallFloatsAreCached() {
    val cache = FloatCache { Any() }

    for (i in 0 until FloatCache.SIZE) {
      val floatValue = i.toFloat() * FloatCache.RESOLUTION
      val first = cache[floatValue]
      val second = cache[floatValue]
      assertSame(first, second, "Failed caching for index $i (float=$floatValue)")
    }
  }

  @Test
  fun testNonMultiplesCallInit() {
    var extraCalls = 0
    val cache = FloatCache {
      extraCalls++
      Any()
    }

    val nonMultiples =
      listOf(0.66f, 0.01f, 1.234f, -0.05f, 600f, Float.NaN, Float.POSITIVE_INFINITY)
    for (f in nonMultiples) {
      val before = extraCalls
      cache[f]
      assertEquals(before + 1, extraCalls, "Expected fresh init for non-multiple $f")
    }
  }
}
