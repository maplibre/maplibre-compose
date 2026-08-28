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
import org.maplibre.compose.map.LocalMapState
import org.maplibre.compose.map.MapState

/**
 * An animated scale bar that appears when the zoom level of the map changes, and then disappears
 * after [visibilityDuration]. This composable wraps [ScaleBar] with visibility animations.
 *
 * This component draws with Compose Foundation alone. The
 * [Material 3 module][org.maplibre.compose.material3] provides a themed version of it.
 *
 * The bar reads the scale and the zoom from [state] and renders nothing until the map has rendered
 * a viewport.
 *
 * @param state the map whose scale the bar shows.
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
  if (state.viewport == null) return
  val zoom = state.camera.zoom
  val visible = remember { MutableTransitionState(true) }

  LaunchedEffect(zoom) {
    // Show ScaleBar
    visible.targetState = true
    delay(visibilityDuration)
    // Hide ScaleBar after timeout period
    visible.targetState = false
  }

  AnimatedVisibility(
    visibleState = visible,
    modifier = modifier,
    enter = enterTransition,
    exit = exitTransition,
  ) {
    ScaleBar(
      state = state,
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
