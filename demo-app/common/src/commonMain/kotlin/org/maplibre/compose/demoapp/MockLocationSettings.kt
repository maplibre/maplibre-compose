package org.maplibre.compose.demoapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import org.maplibre.compose.demoapp.design.ButtonRow
import org.maplibre.compose.demoapp.design.SectionHeader
import org.maplibre.compose.demoapp.design.SliderRow
import org.maplibre.compose.demoapp.design.SwitchRow

@Composable
internal fun MockLocationSettings(state: DemoAppState, onPickPosition: () -> Unit) {
  val location = state.location
  val engine = location.mockEngine
  val sample = engine.sample
  SectionHeader("Position")
  Text(
    text =
      "${(sample.position.latitude * 100000).roundToInt() / 100000.0}°, ${
        (sample.position.longitude * 100000).roundToInt() / 100000.0
      }°",
    style = MaterialTheme.typography.bodyMedium,
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
  )
  if (location.placingMockLocation) {
    ButtonRow("Cancel position selection", location::cancelMockPlacement)
  } else {
    ButtonRow("Set position on map", onPickPosition)
  }
  ButtonRow("Use map center") { location.useMapCenter(state.mapState.cameraPosition.target) }
  SwitchRow("Position accuracy known", sample.positionAccuracyKnown) {
    engine.sample = engine.sample.copy(positionAccuracyKnown = it)
  }
  if (sample.positionAccuracyKnown) {
    SliderRow(
      label = "Position accuracy",
      value = sample.positionAccuracy,
      range = 0f..200f,
      valueLabel = { "${it.roundToInt()} m" },
    ) {
      engine.sample = engine.sample.copy(positionAccuracy = it)
    }
  }

  SectionHeader("Heading")
  SwitchRow("Heading available", sample.headingAvailable) {
    engine.sample = engine.sample.copy(headingAvailable = it)
  }
  if (sample.headingAvailable) {
    BearingDial(sample.bearing, sample.bearingAccuracy.takeIf { sample.bearingAccuracyKnown }) {
      engine.sample = engine.sample.copy(bearing = it)
    }
    SliderRow(
      label = "Bearing",
      value = sample.bearing,
      range = 0f..360f,
      valueLabel = { "${it.roundToInt()}°" },
    ) {
      engine.sample = engine.sample.copy(bearing = it)
    }
    SwitchRow("Bearing accuracy known", sample.bearingAccuracyKnown) {
      engine.sample = engine.sample.copy(bearingAccuracyKnown = it)
    }
    if (sample.bearingAccuracyKnown) {
      SliderRow(
        label = "Bearing accuracy",
        value = sample.bearingAccuracy,
        range = 0f..180f,
        valueLabel = { "±${it.roundToInt()}°" },
      ) {
        engine.sample = engine.sample.copy(bearingAccuracy = it)
      }
    }
  }
}

/** A north-up dial; the adjacent bearing slider provides keyboard and accessibility input. */
@Composable
private fun BearingDial(bearing: Float, accuracy: Float?, onChange: (Float) -> Unit) {
  val primary = MaterialTheme.colorScheme.primary
  val outline = MaterialTheme.colorScheme.outlineVariant
  val currentOnChange by rememberUpdatedState(onChange)
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    Box(Modifier.padding(top = 12.dp).size(192.dp)) {
      Text("N", Modifier.align(Alignment.TopCenter), style = MaterialTheme.typography.labelMedium)
      Text("E", Modifier.align(Alignment.CenterEnd), style = MaterialTheme.typography.labelMedium)
      Text(
        "S",
        Modifier.align(Alignment.BottomCenter),
        style = MaterialTheme.typography.labelMedium,
      )
      Text("W", Modifier.align(Alignment.CenterStart), style = MaterialTheme.typography.labelMedium)
      Canvas(
        Modifier.align(Alignment.Center).size(152.dp).pointerInput(Unit) {
          awaitEachGesture {
            val down = awaitFirstDown()
            fun update(position: Offset) {
              val x = position.x - size.width / 2f
              val y = position.y - size.height / 2f
              if (x * x + y * y < 16f) return
              currentOnChange(((atan2(x, -y) * 180f / PI.toFloat() + 360f) % 360f))
            }
            update(down.position)
            down.consume()
            do {
              val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
              update(change.position)
              change.consume()
            } while (change.pressed)
          }
        }
      ) {
        val radius = size.minDimension / 2f - 2.dp.toPx()
        drawCircle(outline, radius, style = Stroke(1.dp.toPx()))
        if (accuracy != null) {
          drawArc(
            color = primary.copy(alpha = 0.15f),
            startAngle = bearing - 90f - accuracy,
            sweepAngle = accuracy * 2f,
            useCenter = true,
          )
        }
        val radians = (bearing - 90f) * PI.toFloat() / 180f
        val tip = center + Offset(cos(radians), sin(radians)) * radius * 0.85f
        drawLine(primary, center, tip, strokeWidth = 3.dp.toPx())
        drawCircle(primary, 5.dp.toPx(), tip)
        drawCircle(primary, 4.dp.toPx())
      }
    }
  }
}
