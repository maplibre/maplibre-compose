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
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.vectorResource
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.demoapp.generated.photo_camera_24px

/** The soft backdrop behind the controls; it keeps white text readable without opaque boxes. */
private val ControlGradientHeight = 152.dp
private val ControlGradientColor = Color.Black.copy(alpha = 0.45f)

/**
 * The capture controls at the bottom center of the safe area, in camera-app chrome: the aspect
 * presets are plain white text and the white shutter button is the one solid element. They sit side
 * by side while the width allows and wrap into two centered rows on narrow maps. Failures surface
 * as a pill above the controls until the next attempt.
 */
@Composable
internal fun BoxScope.SnapshotControls(
  state: SnapshotterDemoState,
  originDp: DpOffset,
  fullMap: DpSize?,
  onCapture: () -> Unit,
) {
  // Like the scrim, the gradient only draws, so it can reach past this child's bounds: it anchors
  // to the map's bottom edge at the map's full width rather than stopping at the safe area.
  Canvas(Modifier.matchParentSize()) {
    val height = ControlGradientHeight.toPx()
    val bottom = if (fullMap == null) size.height else fullMap.height.toPx() - originDp.y.toPx()
    val left = if (fullMap == null) 0f else -originDp.x.toPx()
    val width = if (fullMap == null) size.width else fullMap.width.toPx()
    drawRect(
      brush =
        Brush.verticalGradient(
          0f to Color.Transparent,
          1f to ControlGradientColor,
          startY = bottom - height,
          endY = bottom,
        ),
      topLeft = Offset(left, bottom - height),
      size = Size(width, height),
    )
  }
  Column(
    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 12.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    StatusPill(state)
    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      itemVerticalAlignment = Alignment.CenterVertically,
    ) {
      CaptureButton(state, onCapture)
      AspectSelector(state)
    }
  }
}

@Composable
private fun CaptureButton(state: SnapshotterDemoState, onCapture: () -> Unit) {
  val capturing = state.status is CaptureStatus.Capturing
  Button(
    onClick = onCapture,
    enabled = state.frameBounds != null && !capturing,
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
  val cleanupFailure = state.cleanupFailure
  val status = state.status
  val message =
    when {
      cleanupFailure != null -> "Snapshot cleanup failed: $cleanupFailure"
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

/**
 * A white flash over the whole map on every capture. Like the scrim, it only draws, so it can reach
 * past this child's layout bounds to the map's edges; it never takes pointer input.
 */
@Composable
internal fun SnapshotFlash(
  tick: Int,
  originDp: DpOffset,
  fullMap: DpSize?,
  modifier: Modifier = Modifier,
) {
  val alpha = remember { Animatable(0f) }
  LaunchedEffect(tick) {
    if (tick == 0) return@LaunchedEffect
    alpha.snapTo(0.55f)
    delay(60)
    alpha.animateTo(0f, tween(350))
  }
  // The alpha read lives in the draw block so the fade redraws without recomposing this child.
  Canvas(modifier.fillMaxSize()) {
    if (alpha.value > 0f) {
      val topLeft =
        if (fullMap == null) Offset.Zero else Offset(-originDp.x.toPx(), -originDp.y.toPx())
      val flashSize =
        if (fullMap == null) size else Size(fullMap.width.toPx(), fullMap.height.toPx())
      drawRect(Color.White, topLeft = topLeft, size = flashSize, alpha = alpha.value)
    }
  }
}
