package org.maplibre.compose.material3.util

import kotlin.test.Test
import kotlin.test.assertEquals

class AngleMathTest {
  @Test
  fun testLeastPositiveResidue() {
    // Test with positive numbers
    assertEquals(1.0, AngleMath.run { 1.0.leastPositiveResidue(5.0) })
    assertEquals(3.0, AngleMath.run { 8.0.leastPositiveResidue(5.0) })

    // Test with negative numbers
    assertEquals(4.0, AngleMath.run { (-1.0).leastPositiveResidue(5.0) })
    assertEquals(2.0, AngleMath.run { (-3.0).leastPositiveResidue(5.0) })

    // Test with zero
    assertEquals(0.0, AngleMath.run { 0.0.leastPositiveResidue(5.0) })

    // Test with large numbers
    assertEquals(1.0, AngleMath.run { 361.0.leastPositiveResidue(360.0) })
    assertEquals(359.0, AngleMath.run { (-1.0).leastPositiveResidue(360.0) })
  }

  @Test
  fun testWrap() {
    // Test with angles in range [0,360)
    assertEquals(0.0, AngleMath.run { 0.0.wrap() })
    assertEquals(90.0, AngleMath.run { 90.0.wrap() })
    assertEquals(180.0, AngleMath.run { 180.0.wrap() })
    assertEquals(270.0, AngleMath.run { 270.0.wrap() })

    // Test with angles >= 360
    assertEquals(0.0, AngleMath.run { 360.0.wrap() })
    assertEquals(1.0, AngleMath.run { 361.0.wrap() })
    assertEquals(0.0, AngleMath.run { 720.0.wrap() })

    // Test with negative angles
    assertEquals(359.0, AngleMath.run { (-1.0).wrap() })
    assertEquals(270.0, AngleMath.run { (-90.0).wrap() })
    assertEquals(180.0, AngleMath.run { (-180.0).wrap() })
    assertEquals(90.0, AngleMath.run { (-270.0).wrap() })
    assertEquals(0.0, AngleMath.run { (-360.0).wrap() })
  }

  @Test
  fun testDiff() {
    // Test with angles in the same quadrant
    assertEquals(-10.0, AngleMath.run { 80.0.diff(90.0) })
    assertEquals(10.0, AngleMath.run { 90.0.diff(80.0) })

    // Test with angles in different quadrants
    assertEquals(-90.0, AngleMath.run { 0.0.diff(90.0) })
    assertEquals(90.0, AngleMath.run { 90.0.diff(0.0) })

    // Test with angles on opposite sides
    assertEquals(180.0, AngleMath.run { 0.0.diff(180.0) })
    assertEquals(180.0, AngleMath.run { 180.0.diff(0.0) })

    // Test with wrap-around cases
    assertEquals(-20.0, AngleMath.run { 350.0.diff(10.0) })
    assertEquals(20.0, AngleMath.run { 10.0.diff(350.0) })

    // Test with negative angles
    assertEquals(-90.0, AngleMath.run { (-45.0).diff(45.0) })
    assertEquals(90.0, AngleMath.run { 45.0.diff(-45.0) })
  }
}
