package org.maplibre.compose.demoapp.design

import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import org.maplibre.compose.demoapp.FrameRateState

/**
 * Shows how fast the map is actually drawing. Frames are requested rather than continuous, so on a
 * still map the number to expect is zero; anything above is work the map was not asked for. The
 * exception is animated layers (see `AnimatedLayerDemo`), which request a frame per tick and hold a
 * steady nonzero rate by design.
 */
@Composable
fun FrameRateListItem(state: FrameRateState, modifier: Modifier = Modifier) {
  LaunchedEffect(state) { state.track() }

  ListItem(
    headlineContent = { Text("Frame rate: ${formatRate(state.framesPerSecond)} fps") },
    supportingContent = { Text("${state.totalFrames} frames drawn while watching") },
    modifier = modifier,
  )
}

/** One decimal below ten, whole numbers above, because the interesting range is near zero. */
private fun formatRate(rate: Double): String {
  if (rate >= 10.0) return rate.roundToInt().toString()
  val tenths = (rate * 10.0).roundToLong()
  return "${tenths / 10}.${tenths % 10}"
}
