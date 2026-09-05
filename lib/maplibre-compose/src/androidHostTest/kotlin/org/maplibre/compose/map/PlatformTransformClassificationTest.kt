package org.maplibre.compose.map

import android.view.MotionEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformTransformClassificationTest {
  @Test
  fun only_supported_classifications_on_api_34_and_later_tag_wrappers() {
    for (classification in
      listOf(MotionEvent.CLASSIFICATION_PINCH, MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE)) {
      assertFalse(isAndroidClassifiedTransform(33, classification))
      assertTrue(isAndroidClassifiedTransform(34, classification))
      assertTrue(isAndroidClassifiedTransform(35, classification))
    }
    for (classification in
      listOf(
        MotionEvent.CLASSIFICATION_NONE,
        MotionEvent.CLASSIFICATION_AMBIGUOUS_GESTURE,
        MotionEvent.CLASSIFICATION_DEEP_PRESS,
      )) {
      assertFalse(isAndroidClassifiedTransform(34, classification))
    }
  }
}
