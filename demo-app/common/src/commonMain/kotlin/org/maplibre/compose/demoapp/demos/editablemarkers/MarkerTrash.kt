package org.maplibre.compose.demoapp.demos.editablemarkers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.demoapp.generated.delete_24px

@Composable
internal fun MarkerTrash(
  visible: Boolean,
  overTrash: Boolean,
  needsExit: Boolean,
  onBoundsChange: (Rect?) -> Unit,
  modifier: Modifier = Modifier,
) {
  AnimatedVisibility(
    visible = visible,
    modifier = modifier,
    enter =
      fadeIn(tween(120)) +
        scaleIn(initialScale = 0.8f, animationSpec = spring(dampingRatio = 0.6f)),
    exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.8f),
  ) {
    DisposableEffect(Unit) { onDispose { onBoundsChange(null) } }
    val targetScale by animateFloatAsState(if (overTrash) 1.1f else 1f, spring(dampingRatio = 0.5f))
    val targetColor by
      animateColorAsState(
        if (overTrash) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.surfaceContainerHigh
      )
    val targetContent by
      animateColorAsState(
        if (overTrash) MaterialTheme.colorScheme.onError
        else MaterialTheme.colorScheme.onSurfaceVariant
      )
    Surface(
      modifier =
        Modifier.width(200.dp)
          .onGloballyPositioned { onBoundsChange(it.boundsInRoot()) }
          .graphicsLayer {
            scaleX = targetScale
            scaleY = targetScale
          },
      shape = CircleShape,
      color = targetColor,
      contentColor = targetContent,
      shadowElevation = 6.dp,
    ) {
      Row(
        Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(painterResource(Res.drawable.delete_24px), contentDescription = null)
        Text(
          when {
            overTrash -> "Release to delete"
            needsExit -> "Move away first"
            else -> "Drag to delete"
          },
          style = MaterialTheme.typography.labelLarge,
        )
      }
    }
  }
}

internal class MarkerTrashTarget(private val start: Offset) {
  private var initialized = false
  var needsExit = false
    private set

  fun update(pointer: Offset, bounds: Rect?): Boolean {
    if (bounds == null) return false
    // Layout can arrive after the drag starts. Check the original press, not the first move.
    if (!initialized) {
      needsExit = bounds.contains(start)
      initialized = true
    }

    val inside = bounds.contains(pointer)
    if (!inside) needsExit = false
    return inside && !needsExit
  }
}
