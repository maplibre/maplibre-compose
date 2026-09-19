package org.maplibre.compose.demoapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
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
    ButtonRow("Cancel position selection", onClick = location::cancelMockPlacement)
  } else {
    ButtonRow("Set position on map", onClick = onPickPosition)
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

private const val BearingKeyStepDegrees = 5f

/**
 * A north-up dial. Dragging sets the bearing; arrow keys step it, and accessibility services set it
 * as a progress value.
 */
@Composable
private fun BearingDial(bearing: Float, accuracy: Float?, onChange: (Float) -> Unit) {
  val primary = MaterialTheme.colorScheme.primary
  val outline = MaterialTheme.colorScheme.outlineVariant
  val currentOnChange by rememberUpdatedState(onChange)
  val currentBearing by rememberUpdatedState(bearing)
  val interactionSource = remember { MutableInteractionSource() }
  val focused by interactionSource.collectIsFocusedAsState()
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    Text(
      "Bearing ${bearing.roundToInt()}°",
      style = MaterialTheme.typography.bodyLarge,
      modifier = Modifier.padding(top = 8.dp),
    )
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
        Modifier.align(Alignment.Center)
          .size(152.dp)
          .semantics {
            contentDescription = "Bearing"
            setProgress { value ->
              currentOnChange(value.coerceIn(0f, 360f))
              true
            }
          }
          .progressSemantics(bearing, 0f..360f)
          .focusable(interactionSource = interactionSource)
          .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            val step =
              when (event.key) {
                Key.DirectionRight,
                Key.DirectionUp -> BearingKeyStepDegrees
                Key.DirectionLeft,
                Key.DirectionDown -> -BearingKeyStepDegrees
                else -> return@onKeyEvent false
              }
            currentOnChange((currentBearing + step + 360f) % 360f)
            true
          }
          .pointerInput(Unit) {
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
        drawCircle(
          if (focused) primary else outline,
          radius,
          style = Stroke(if (focused) 2.dp.toPx() else 1.dp.toPx()),
        )
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
