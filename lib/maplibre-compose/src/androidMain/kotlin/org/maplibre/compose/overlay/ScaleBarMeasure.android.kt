package org.maplibre.compose.overlay

import android.icu.util.LocaleData
import android.icu.util.ULocale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration

@Composable
internal actual fun systemDefaultPrimaryMeasure(): ScaleBarMeasure? {
  if (android.os.Build.VERSION.SDK_INT < 28) return null
  val locales = LocalConfiguration.current.locales
  if (locales.isEmpty) return null
  val locale = locales[0]
  return remember(locale) {
    when (LocaleData.getMeasurementSystem(ULocale.forLocale(locale))) {
      LocaleData.MeasurementSystem.SI -> ScaleBarMeasure.Metric
      LocaleData.MeasurementSystem.US -> ScaleBarMeasure.FeetAndMiles
      LocaleData.MeasurementSystem.UK -> ScaleBarMeasure.YardsAndMiles
      else -> null
    }
  }
}
