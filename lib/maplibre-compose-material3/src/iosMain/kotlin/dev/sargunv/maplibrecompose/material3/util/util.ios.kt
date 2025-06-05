package dev.sargunv.maplibrecompose.material3.util

import androidx.compose.runtime.Composable
import dev.sargunv.maplibrecompose.material3.controls.ScaleBarMeasure

@Composable internal actual fun systemDefaultPrimaryMeasure(): ScaleBarMeasure? = null

/*
TODO iOS developer: needs to be implemented in Swift with #available
internal actual fun scaleBareMeasurePreference(): ScaleBarMeasure? {
  val system = NSLocale.currentLocale.measurementSystem
  return when (userlocale.measurementSystem) {
    Locale.MeasurementSystem.metric -> ScaleBarMeasure.Metric
    Locale.MeasurementSystem.uk -> ScaleBarMeasure.YardsAndMiles
    Locale.MeasurementSystem.us -> ScaleBarMeasure.FeetAndMiles
    else -> null
  }
}
*/
