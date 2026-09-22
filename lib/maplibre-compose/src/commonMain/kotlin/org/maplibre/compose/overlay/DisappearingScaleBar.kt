package org.maplibre.compose.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * An animated scale bar that appears when the [zoom] level of the map changes, and then disappears
 * after [visibilityDuration].
 *
 * [metersPerDp] and [zoom] are read from state: a zoom change restarts the visibility timer and a
 * scale change redraws the bar, neither recomposing it. The map's camera changes every frame of an
 * animation, so read it inside the lambdas rather than capturing a value.
 *
 * The Material 3 module provides a themed version.
 *
 * @param metersPerDp how many meters are displayed in one device independent pixel (dp), i.e. the
 *   scale. See [CameraPosition.metersPerDp][org.maplibre.compose.camera.CameraPosition.metersPerDp]
 * @param zoom zoom level of the map
 * @param modifier the [Modifier] to be applied to this layout node
 * @param measures which measures to show on the scale bar. The default follows the system settings,
 *   or otherwise the user's locale.
 * @param color scale bar and text color.
 * @param haloColor halo for better visibility when displayed on top of the map
 * @param haloWidth scale bar and text halo width
 * @param barWidth scale bar width
 * @param textStyle Text style. The font size determines the scale bar height.
 * @param alignment horizontal alignment of the scale bar and text
 * @param visibilityDuration how long it should be visible after the zoom changed
 * @param enterTransition EnterTransition(s) used for the appearing animation
 * @param exitTransition ExitTransition(s) used for the disappearing animation
 */
@Composable
public fun DisappearingScaleBar(
  metersPerDp: () -> Double,
  zoom: () -> Double,
  modifier: Modifier = Modifier,
  measures: ScaleBarMeasures = ScaleBarDefaults.measures(),
  color: Color = ScaleBarDefaults.ContentColor,
  haloColor: Color = ScaleBarDefaults.HaloColor,
  haloWidth: Dp = ScaleBarDefaults.HaloWidth,
  barWidth: Dp = ScaleBarDefaults.BarWidth,
  textStyle: TextStyle = ScaleBarDefaults.ContentTextStyle,
  alignment: Alignment.Horizontal = Alignment.Start,
  visibilityDuration: Duration = 3.seconds,
  enterTransition: EnterTransition = fadeIn(),
  exitTransition: ExitTransition = fadeOut(),
) {
  val visible = remember { MutableTransitionState(true) }
  val currentVisibilityDuration by rememberUpdatedState(visibilityDuration)
  // Keyed on nothing: a new zoom lambda must not restart the timer and show the bar.
  val currentZoom by rememberUpdatedState(zoom)

  LaunchedEffect(Unit) {
    snapshotFlow { currentZoom() }
      .collectLatest {
        visible.targetState = true
        delay(currentVisibilityDuration)
        visible.targetState = false
      }
  }

  AnimatedVisibility(
    visibleState = visible,
    modifier = modifier,
    enter = enterTransition,
    exit = exitTransition,
  ) {
    ScaleBar(
      metersPerDp = metersPerDp,
      measures = measures,
      haloColor = haloColor,
      haloWidth = haloWidth,
      color = color,
      barWidth = barWidth,
      textStyle = textStyle,
      alignment = alignment,
    )
  }
}
