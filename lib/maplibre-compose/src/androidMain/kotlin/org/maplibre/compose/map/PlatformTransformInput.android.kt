package org.maplibre.compose.map

import android.os.Build
import android.view.MotionEvent
import androidx.compose.ui.input.pointer.PointerEvent

internal actual fun isClassifiedPlatformTransform(event: PointerEvent): Boolean =
  isAndroidClassifiedTransform(Build.VERSION.SDK_INT, event.classification)

internal fun isAndroidClassifiedTransform(sdk: Int, classification: Int): Boolean =
  sdk >= 34 &&
    (classification == MotionEvent.CLASSIFICATION_PINCH ||
      classification == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE)
