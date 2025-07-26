package org.maplibre.compose.material3.util

import android.icu.util.LocaleData
import android.icu.util.ULocale
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import org.maplibre.compose.material3.ScaleBarMeasure

@Composable
internal actual fun systemDefaultPrimaryMeasure(): ScaleBarMeasure? {
  if (android.os.Build.VERSION.SDK_INT < 28) return null
  val locales = LocalConfiguration.current.locales
  if (locales.isEmpty) return null
  return when (LocaleData.getMeasurementSystem(ULocale.forLocale(locales[0]))) {
    LocaleData.MeasurementSystem.SI -> ScaleBarMeasure.Metric
    LocaleData.MeasurementSystem.US -> ScaleBarMeasure.FeetAndMiles
    LocaleData.MeasurementSystem.UK -> ScaleBarMeasure.YardsAndMiles
    else -> null
  }
}
