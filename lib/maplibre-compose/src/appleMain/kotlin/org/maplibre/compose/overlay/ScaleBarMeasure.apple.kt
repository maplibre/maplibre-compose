package org.maplibre.compose.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.intl.Locale
import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleMeasurementSystem
import platform.Foundation.currentLocale

@Composable
internal actual fun systemDefaultPrimaryMeasure(): ScaleBarMeasure? =
  remember(Locale.current) {
    val measurementSystem =
      NSLocale.currentLocale.objectForKey(NSLocaleMeasurementSystem) as? String
    scaleBarMeasureForAppleMeasurementSystem(measurementSystem)
  }
