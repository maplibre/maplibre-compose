package org.maplibre.compose.overlay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EllipseIntersectionTest {
  private val square = Rect(0f, 0f, 100f, 100f)

  @Test
  fun targetInsideTheEllipseHasNoIntersection() {
    assertNull(findEllipseIntersection(square, Offset(60f, 60f)))
  }

  @Test
  fun targetToTheRightIntersectsTheRightEdge() {
    val intersection = assertNotNull(findEllipseIntersection(square, Offset(300f, 50f)))
    assertEquals(100f, intersection.position.x, absoluteTolerance = 0.001f)
    assertEquals(50f, intersection.position.y, absoluteTolerance = 0.001f)
    assertEquals(PI / 2, intersection.angleRadians, absoluteTolerance = 0.001)
  }

  @Test
  fun targetAboveIntersectsTheTopEdge() {
    val intersection = assertNotNull(findEllipseIntersection(square, Offset(50f, -100f)))
    assertEquals(50f, intersection.position.x, absoluteTolerance = 0.001f)
    assertEquals(0f, intersection.position.y, absoluteTolerance = 0.001f)
    assertEquals(0.0, intersection.angleRadians, absoluteTolerance = 0.001)
  }

  @Test
  fun wideAreaScalesTheIntersectionToTheEllipse() {
    val wide = Rect(0f, 0f, 200f, 100f)
    val intersection = assertNotNull(findEllipseIntersection(wide, Offset(500f, 50f)))
    assertEquals(200f, intersection.position.x, absoluteTolerance = 0.001f)
    assertEquals(50f, intersection.position.y, absoluteTolerance = 0.001f)
  }

  @Test
  fun offsetAreaKeepsTheIntersectionOnItsOwnEllipse() {
    val inset = Rect(40f, 60f, 300f, 300f)
    val intersection = assertNotNull(findEllipseIntersection(inset, Offset(170f, -100f)))
    assertEquals(170f, intersection.position.x, absoluteTolerance = 0.5f)
    assertEquals(60f, intersection.position.y, absoluteTolerance = 0.5f)
  }

  @Test
  fun childPointingUpAnchorsItsTopCenterOnTheIntersection() {
    val intersection = EllipseIntersection(Offset(50f, 0f), angleRadians = 0.0)
    val topLeft = placedTowardsTopLeft(intersection, width = 20, height = 20)
    assertEquals(40, topLeft.x)
    assertEquals(0, topLeft.y)
  }

  @Test
  fun childPointingRightAnchorsItsRightCenterOnTheIntersection() {
    val intersection = EllipseIntersection(Offset(100f, 50f), angleRadians = PI / 2)
    val topLeft = placedTowardsTopLeft(intersection, width = 20, height = 20)
    assertEquals(80, topLeft.x)
    assertEquals(40, topLeft.y)
  }
}
