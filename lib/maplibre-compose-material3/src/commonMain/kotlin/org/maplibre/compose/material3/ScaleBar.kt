package org.maplibre.compose.material3

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.maplibre.compose.map.LocalMapState
import org.maplibre.compose.map.MapState
import org.maplibre.compose.material3.util.backgroundColorFor
import org.maplibre.compose.overlay.DisappearingScaleBar as BaseDisappearingScaleBar
import org.maplibre.compose.overlay.ScaleBar as BaseScaleBar
import org.maplibre.compose.overlay.ScaleBarDefaults
import org.maplibre.compose.overlay.ScaleBarMeasures

/**
 * A scale bar composable that shows the current scale of the map in feet, meters or feet and meters
 * when zoomed in to the map, changing to miles and kilometers, respectively, when zooming out.
 *
 * This is [org.maplibre.compose.overlay.ScaleBar] with its colors and typography taken from the
 * Material 3 theme.
 *
 * The bar reads the scale from [state] and renders nothing until the map has rendered a viewport.
 *
 * @param state the map whose scale the bar shows. Defaults to the map that
 *   [LocalMapState][org.maplibre.compose.map.LocalMapState] provides.
 * @param measures The measures to show. The default follows the system settings, or otherwise the
 *   user's locale.
 * @param haloColor A halo color that keeps the bar readable over the map.
 * @param textStyle The text style. The text size sets the size of the whole bar.
 */
@Composable
public fun ScaleBar(
  state: MapState = LocalMapState.current,
  modifier: Modifier = Modifier,
  measures: ScaleBarMeasures = ScaleBarDefaults.measures(),
  color: Color = LocalContentColor.current,
  haloColor: Color = backgroundColorFor(color),
  haloWidth: Dp = 0.dp,
  barWidth: Dp = 2.dp,
  textStyle: TextStyle = MaterialTheme.typography.labelSmall,
  alignment: Alignment.Horizontal = Alignment.Start,
) {
  BaseScaleBar(
    state = state,
    modifier = modifier,
    measures = measures,
    color = color,
    haloColor = haloColor,
    haloWidth = haloWidth,
    barWidth = barWidth,
    textStyle = textStyle,
    alignment = alignment,
  )
}

/**
 * An animated scale bar that appears when the zoom level of the map changes, and then disappears
 * after [visibilityDuration]. This composable wraps [ScaleBar] with visibility animations.
 *
 * This is [org.maplibre.compose.overlay.DisappearingScaleBar] with its colors and typography taken
 * from the Material 3 theme.
 *
 * The bar reads the scale and the zoom from [state] and renders nothing until the map has rendered
 * a viewport.
 *
 * @param state the map whose scale the bar shows. Defaults to the map that
 *   [LocalMapState][org.maplibre.compose.map.LocalMapState] provides.
 * @param measures The measures to show. The default follows the system settings, or otherwise the
 *   user's locale.
 * @param haloColor A halo color that keeps the bar readable over the map.
 * @param textStyle The text style. The text size sets the size of the whole bar.
 * @param visibilityDuration How long the bar stays visible after the zoom changes.
 */
@Composable
public fun DisappearingScaleBar(
  state: MapState = LocalMapState.current,
  modifier: Modifier = Modifier,
  measures: ScaleBarMeasures = ScaleBarDefaults.measures(),
  color: Color = LocalContentColor.current,
  haloColor: Color = backgroundColorFor(color),
  haloWidth: Dp = 0.dp,
  barWidth: Dp = 2.dp,
  textStyle: TextStyle = MaterialTheme.typography.labelMedium,
  alignment: Alignment.Horizontal = Alignment.Start,
  visibilityDuration: Duration = 3.seconds,
  enterTransition: EnterTransition = fadeIn(),
  exitTransition: ExitTransition = fadeOut(),
) {
  BaseDisappearingScaleBar(
    state = state,
    modifier = modifier,
    measures = measures,
    color = color,
    haloColor = haloColor,
    haloWidth = haloWidth,
    barWidth = barWidth,
    textStyle = textStyle,
    alignment = alignment,
    visibilityDuration = visibilityDuration,
    enterTransition = enterTransition,
    exitTransition = exitTransition,
  )
}
