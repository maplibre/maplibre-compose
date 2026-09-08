package org.maplibre.compose.demoapp.demos

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

internal data class MarkerMotion(
  val alpha: Float,
  val scale: Float,
  val rotation: Float,
  val offsetY: Float,
  val shadowRadius: Float,
  val highlight: Float,
)

@Composable
internal fun rememberMarkerMotion(
  marker: EditableMarker,
  selected: Boolean,
  hovered: Boolean,
  pressed: Boolean,
  dragging: Boolean,
  overTrash: Boolean,
  dragTilt: Float,
  onRemoved: () -> Unit,
): MarkerMotion {
  // Keep the source alive until the exit animation finishes.
  val presence = remember { Animatable(0f) }
  LaunchedEffect(marker.removing) {
    if (marker.removing) {
      presence.animateTo(0f, tween(220))
      onRemoved()
    } else presence.animateTo(1f, spring(dampingRatio = 0.48f, stiffness = 260f))
  }

  // Repeated selections and color changes can replay this feedback without changing selection.
  val rock = remember { Animatable(0f) }
  LaunchedEffect(marker.bounce) {
    if (marker.bounce > 0) {
      rock.snapTo(-14f)
      rock.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 420f))
    }
  }

  // Lift on hover/drag, compress on press, and shrink into the armed trash target.
  val lift by
    animateFloatAsState(
      when {
        dragging -> 18f
        pressed -> -2f
        hovered -> 6f
        selected -> 3f
        else -> 0f
      },
      spring(dampingRatio = 0.5f, stiffness = 360f),
    )
  val scale by
    animateFloatAsState(
      when {
        dragging && overTrash -> 0.75f
        pressed -> 0.86f
        dragging -> 1.18f
        hovered -> 1.12f
        selected -> 1.06f
        else -> 1f
      },
      spring(dampingRatio = 0.45f, stiffness = 500f),
    )
  val tilt by animateFloatAsState(if (dragging) dragTilt else 0f, spring(stiffness = 450f))
  val highlight by
    animateFloatAsState(if (selected || hovered) 1f else 0f, spring(stiffness = 350f))

  val alpha = presence.value.coerceIn(0f, 1f)
  return MarkerMotion(
    alpha = alpha,
    scale = (presence.value * scale).coerceAtLeast(0.01f),
    rotation = rock.value + tilt,
    offsetY = -lift - (1f - alpha) * 32f,
    shadowRadius = 7f + lift.coerceAtLeast(0f) * 0.15f,
    highlight = highlight,
  )
}
