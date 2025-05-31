package org.maplibre.maplibrecompose.material3.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.intl.Locale
import org.maplibre.maplibrecompose.material3.controls.ScaleBarMeasure
import org.maplibre.maplibrecompose.material3.controls.ScaleBarMeasure.FeetAndMiles
import org.maplibre.maplibrecompose.material3.controls.ScaleBarMeasure.Metric
import org.maplibre.maplibrecompose.material3.controls.ScaleBarMeasure.YardsAndMiles
import org.maplibre.maplibrecompose.material3.controls.ScaleBarMeasures

/** use system locale APIs for the primary scale bar measure */
@Composable internal expect fun systemDefaultPrimaryMeasure(): ScaleBarMeasure?

/** if the system APIs don't provide a primary measure, fall back to our hardcoded lists */
internal fun fallbackDefaultPrimaryMeasure(region: String?): ScaleBarMeasure =
  when (region) {
    in _root_ide_package_.org.maplibre.maplibrecompose.material3.util.regionsUsingFeetAndMiles -> FeetAndMiles
    in _root_ide_package_.org.maplibre.maplibrecompose.material3.util.regionsUsingYardsAndMiles -> YardsAndMiles
    else -> Metric
  }

/** countries using non-metric units will see both systems by default */
internal fun defaultSecondaryMeasure(primary: ScaleBarMeasure, region: String?): ScaleBarMeasure? =
  when (primary) {
    FeetAndMiles -> Metric
    YardsAndMiles -> Metric
    Metric ->
      when (region) {
        in _root_ide_package_.org.maplibre.maplibrecompose.material3.util.regionsUsingFeetAndMiles -> FeetAndMiles
        in _root_ide_package_.org.maplibre.maplibrecompose.material3.util.regionsUsingYardsAndMiles -> YardsAndMiles
        else -> null
      }
    else -> null // should never happen because the primary is always one of the above
  }

internal val regionsUsingFeetAndMiles =
  setOf(
    // United states and its unincorporated territories
    "US",
    "AS",
    "GU",
    "MP",
    "PR",
    "VI",
    // former United states territories / Compact of Free Association
    "FM",
    "MH",
    "PW",
    // Liberia
    "LR",
  )

internal val regionsUsingYardsAndMiles =
  setOf(
    // United kingdom with its overseas territories and crown dependencies
    "GB",
    "AI",
    "BM",
    "FK",
    "GG",
    "GI",
    "GS",
    "IM",
    "IO",
    "JE",
    "KY",
    "MS",
    "PN",
    "SH",
    "TC",
    "VG",
    // former British overseas territories / colonies
    "BS",
    "BZ",
    "GD",
    "KN",
    "VC",
    // Myanmar
    "MM",
  )

/**
 * default scale bar measures to use, depending on the user's locale (or system preferences, if
 * available)
 */
@Composable
public fun defaultScaleBarMeasures(): ScaleBarMeasures {
  val region = Locale.current.region
  val primary = systemDefaultPrimaryMeasure() ?: _root_ide_package_.org.maplibre.maplibrecompose.material3.util.fallbackDefaultPrimaryMeasure(
    region
  )
  return ScaleBarMeasures(primary = primary, secondary = _root_ide_package_.org.maplibre.maplibrecompose.material3.util.defaultSecondaryMeasure(
    primary,
    region
  )
  )
}
