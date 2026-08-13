package org.maplibre.compose.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import org.maplibre.compose.overlay.ScaleBarMeasure.FeetAndMiles
import org.maplibre.compose.overlay.ScaleBarMeasure.Metric
import org.maplibre.compose.overlay.ScaleBarMeasure.YardsAndMiles

/** Which measures to show on the scale bar. */
@Immutable
public data class ScaleBarMeasures(
  val primary: ScaleBarMeasure,
  val secondary: ScaleBarMeasure? = null,
)

/** use system locale APIs for the primary scale bar measure */
@Composable internal expect fun systemDefaultPrimaryMeasure(): ScaleBarMeasure?

/** if the system APIs don't provide a primary measure, fall back to our hardcoded lists */
internal fun fallbackDefaultPrimaryMeasure(region: String?): ScaleBarMeasure =
  when (region) {
    in regionsUsingFeetAndMiles -> FeetAndMiles
    in regionsUsingYardsAndMiles -> YardsAndMiles
    else -> Metric
  }

/** countries using non-metric units will see both systems by default */
internal fun defaultSecondaryMeasure(primary: ScaleBarMeasure, region: String?): ScaleBarMeasure? =
  when (primary) {
    FeetAndMiles -> Metric
    YardsAndMiles -> Metric
    Metric ->
      when (region) {
        in regionsUsingFeetAndMiles -> FeetAndMiles
        in regionsUsingYardsAndMiles -> YardsAndMiles
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
