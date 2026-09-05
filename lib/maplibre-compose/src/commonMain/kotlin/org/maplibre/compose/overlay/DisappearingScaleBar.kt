package org.maplibre.compose.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * An animated scale bar that appears when the [zoom] level of the map changes, and then disappears
 * after [visibilityDuration].
 *
 * The Material 3 module provides a themed version.
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
 * @param textStyle Text style. The font size determines the scale bar height.
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

  LaunchedEffect(zoom) {
    visible.targetState = true
    delay(visibilityDuration)
    visible.targetState = false
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
