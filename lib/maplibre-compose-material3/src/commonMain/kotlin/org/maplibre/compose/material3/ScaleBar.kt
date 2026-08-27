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
 * An overload takes the scale as a number instead.
 *
 * @param state the map whose scale the bar shows. Defaults to the map that
 *   [LocalMapState][org.maplibre.compose.map.LocalMapState] provides.
 * @param modifier the [Modifier] to be applied to this layout node
 * @param measures which measures to show on the scale bar. The default follows the system settings,
 *   or otherwise the user's locale.
 * @param color scale bar and text color.
 * @param haloColor halo for better visibility when displayed on top of the map
 * @param haloWidth scale bar and text halo width
 * @param barWidth scale bar width
 * @param textStyle the text style. The text size is the deciding factor how large the scale bar is
 *   is displayed.
 * @param alignment horizontal alignment of the scale bar and text
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
  val metersPerDp = state.viewport?.metersPerDpAtTarget ?: return
  ScaleBar(
    metersPerDp = metersPerDp,
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
 * A scale bar composable that shows the current scale of the map in feet, meters or feet and meters
 * when zoomed in to the map, changing to miles and kilometers, respectively, when zooming out.
 *
 * This is [org.maplibre.compose.overlay.ScaleBar] with its colors and typography taken from the
 * Material 3 theme.
 *
 * This overload takes the scale as a number, for a caller that computes its own. The overload that
 * takes a [MapState] reads the scale from the map.
 *
 * @param metersPerDp how many meters are displayed in one device independent pixel (dp), i.e. the
 *   scale. See
 *   [Viewport.metersPerDpAtTarget][org.maplibre.compose.camera.Viewport.metersPerDpAtTarget]
 * @param modifier the [Modifier] to be applied to this layout node
 * @param measures which measures to show on the scale bar. The default follows the system settings,
 *   or otherwise the user's locale.
 * @param color scale bar and text color.
 * @param haloColor halo for better visibility when displayed on top of the map
 * @param haloWidth scale bar and text halo width
 * @param barWidth scale bar width
 * @param textStyle the text style. The text size is the deciding factor how large the scale bar is
 *   is displayed.
 * @param alignment horizontal alignment of the scale bar and text
 */
@Composable
public fun ScaleBar(
  metersPerDp: Double,
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
    metersPerDp = metersPerDp,
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
 * a viewport. An overload takes both as numbers instead.
 *
 * @param state the map whose scale the bar shows. Defaults to the map that
 *   [LocalMapState][org.maplibre.compose.map.LocalMapState] provides.
 * @param modifier the [Modifier] to be applied to this layout node
 * @param measures which measures to show on the scale bar. The default follows the system settings,
 *   or otherwise the user's locale.
 * @param color scale bar and text color.
 * @param haloColor halo for better visibility when displayed on top of the map
 * @param haloWidth scale bar and text halo width
 * @param barWidth scale bar width
 * @param textStyle the text style. The text size is the deciding factor how large the scale bar is
 *   is displayed.
 * @param alignment horizontal alignment of the scale bar and text
 * @param visibilityDuration how long it should be visible after the zoom changed
 * @param enterTransition EnterTransition(s) used for the appearing animation
 * @param exitTransition ExitTransition(s) used for the disappearing animation
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
  val metersPerDp = state.viewport?.metersPerDpAtTarget ?: return
  DisappearingScaleBar(
    metersPerDp = metersPerDp,
    zoom = state.camera.zoom,
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

/**
 * An animated scale bar that appears when the [zoom] level of the map changes, and then disappears
 * after [visibilityDuration]. This composable wraps [ScaleBar] with visibility animations.
 *
 * This is [org.maplibre.compose.overlay.DisappearingScaleBar] with its colors and typography taken
 * from the Material 3 theme.
 *
 * This overload takes the scale and the zoom as numbers, for a caller that computes its own. The
 * overload that takes a [MapState] reads both from the map.
 *
 * @param metersPerDp how many meters are displayed in one device independent pixel (dp), i.e. the
 *   scale. See
 *   [Viewport.metersPerDpAtTarget][org.maplibre.compose.camera.Viewport.metersPerDpAtTarget]
 * @param zoom zoom level of the map
 * @param modifier the [Modifier] to be applied to this layout node
 * @param measures which measures to show on the scale bar. The default follows the system settings,
 *   or otherwise the user's locale.
 * @param color scale bar and text color.
 * @param haloColor halo for better visibility when displayed on top of the map
 * @param haloWidth scale bar and text halo width
 * @param barWidth scale bar width
 * @param textStyle the text style. The text size is the deciding factor how large the scale bar is
 *   is displayed.
 * @param alignment horizontal alignment of the scale bar and text
 * @param visibilityDuration how long it should be visible after the zoom changed
 * @param enterTransition EnterTransition(s) used for the appearing animation
 * @param exitTransition ExitTransition(s) used for the disappearing animation
 */
@Composable
public fun DisappearingScaleBar(
  metersPerDp: Double,
  zoom: Double,
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
    metersPerDp = metersPerDp,
    zoom = zoom,
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
