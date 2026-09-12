package org.maplibre.compose.demoapp.demos.snapshotter

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.vectorResource
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.demoapp.generated.photo_camera_24px

/** The thumbnail is a measured slot alongside the shutter, not an independently positioned dock. */
@Composable
internal fun SnapshotControls(
  state: SnapshotterDemoState,
  canCapture: Boolean,
  modifier: Modifier = Modifier,
  onCapture: () -> Unit,
  onDockPositioned: (LayoutCoordinates) -> Unit,
) {
  Column(
    modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    StatusPill(state)
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      itemVerticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        CaptureButton(state, canCapture, onCapture)
        Box(Modifier.size(56.dp).onGloballyPositioned(onDockPositioned))
      }
      AspectSelector(state)
    }
  }
}

@Composable
private fun CaptureButton(state: SnapshotterDemoState, canCapture: Boolean, onCapture: () -> Unit) {
  val capturing = state.status is CaptureStatus.Capturing
  Button(
    onClick = onCapture,
    enabled = canCapture && !capturing,
    colors =
      ButtonDefaults.buttonColors(
        containerColor = Color.White,
        contentColor = Color.Black,
        disabledContainerColor = Color.White.copy(alpha = 0.4f),
        disabledContentColor = Color.Black.copy(alpha = 0.6f),
      ),
  ) {
    if (capturing) {
      CircularProgressIndicator(
        modifier = Modifier.size(18.dp),
        color = LocalContentColor.current,
        strokeWidth = 2.dp,
      )
    } else {
      Icon(
        vectorResource(Res.drawable.photo_camera_24px),
        contentDescription = null,
        modifier = Modifier.size(18.dp),
      )
    }
    Text(
      if (capturing) "Capturing…" else "Take snapshot",
      modifier = Modifier.padding(start = 8.dp),
    )
  }
}

/**
 * The aspect presets as a camera-style mode row: plain text over the dimmed map, the selection
 * shown with weight and brightness. Tapping one animates the frame itself, which is the real
 * preview. The row scrolls on hosts too narrow for it, like the Wear map.
 */
@Composable
private fun AspectSelector(state: SnapshotterDemoState) {
  Row(
    modifier = Modifier.selectableGroup().horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    SnapshotAspect.entries.forEach { aspect ->
      val isSelected = state.aspect == aspect
      val color by
        animateColorAsState(
          if (isSelected) Color.White else Color.White.copy(alpha = 0.62f),
          label = "aspect label color",
        )
      Text(
        aspect.label,
        maxLines = 1,
        color = color,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        modifier =
          Modifier.selectable(selected = isSelected, role = Role.RadioButton) {
              state.aspect = aspect
            }
            .padding(horizontal = 10.dp, vertical = 10.dp),
      )
    }
  }
}

@Composable
private fun StatusPill(state: SnapshotterDemoState) {
  val status = state.status
  val message =
    when {
      status is CaptureStatus.Failed -> status.message
      else -> null
    }
  AnimatedVisibility(
    visible = message != null,
    enter = expandVertically(),
    exit = shrinkVertically(),
  ) {
    Surface(
      shape = MaterialTheme.shapes.medium,
      color = MaterialTheme.colorScheme.errorContainer,
      contentColor = MaterialTheme.colorScheme.onErrorContainer,
      shadowElevation = 3.dp,
    ) {
      Text(
        message.orEmpty(),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
      )
    }
  }
}

/** A shutter flash drawn inside the full-map overlay. */
@Composable
internal fun SnapshotFlash(tick: Int) {
  val alpha = remember { Animatable(0f) }
  LaunchedEffect(tick) {
    if (tick == 0) return@LaunchedEffect
    alpha.snapTo(0.55f)
    delay(60)
    alpha.animateTo(0f, tween(350))
  }
  Canvas(Modifier.fillMaxSize()) { drawRect(Color.White, alpha = alpha.value) }
}
