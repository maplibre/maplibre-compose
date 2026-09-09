package org.maplibre.compose.demoapp.demos.editablemarkers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.demoapp.generated.check_24px
import org.maplibre.compose.overlay.MapOverlayScope

@Composable
internal fun MapOverlayScope.MarkerEditors(state: EditableMarkersState, mapSize: IntSize) =
  with(state) {
    // Keep the editor inside the usable map, flipping below pins near the top edge.
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val left =
      with(density) { contentWindowInsets.getLeft(this, layoutDirection).toDp().value } + 8f
    val right =
      with(density) {
        (mapSize.width - contentWindowInsets.getRight(this, layoutDirection)).toDp().value
      } - 8f
    val top = with(density) { contentWindowInsets.getTop(this).toDp().value } + 8f
    val editorWidth = (right - left).coerceIn(0f, 272f)

    for (marker in markers) key(marker.id) {
      // The overlay tracks camera frames; use the same projection to keep the editor on screen.
      val screen =
        remember(marker.position, mapState.cameraPosition, mapSize) {
          mapState.screenLocationFromPosition(marker.position)
        }
      var editorHeight by remember { mutableStateOf(66f) }
      val shift = screen?.let { markerEditorShift(it.x.value, editorWidth, left, right) } ?: 0f
      val below = screen != null && screen.y.value - 76f - editorHeight < top

      AnimatedMarkerEditor(
        marker = marker,
        visible = editingId == marker.id && draggingId == null && !marker.removing,
        below = below,
        shift = shift,
        onClose = { editingId = null },
        modifier =
          Modifier.placedAt(
              marker.position,
              if (below) Alignment.TopCenter else Alignment.BottomCenter,
            )
            .padding(
              top = if (below) (32f + 14f * textScale).dp else 0.dp,
              bottom = if (below) 0.dp else 76.dp,
            ),
        editorModifier =
          Modifier.width(editorWidth.dp).absoluteOffset(x = shift.dp).onGloballyPositioned {
            editorHeight = with(density) { it.size.height.toDp().value }
          },
      )
    }
  }

private fun markerEditorShift(anchorX: Float, width: Float, left: Float, right: Float): Float {
  if (right - left < width) return (left + right) / 2f - anchorX
  return anchorX.coerceIn(left + width / 2f, right - width / 2f) - anchorX
}

@Composable
private fun AnimatedMarkerEditor(
  marker: EditableMarker,
  visible: Boolean,
  below: Boolean,
  shift: Float,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  editorModifier: Modifier = Modifier,
) {
  AnimatedVisibility(
    visibleState =
      remember { MutableTransitionState(false) }
        .apply {
          targetState = visible
        },
    modifier = modifier,
    enter =
      fadeIn(tween(130)) +
        scaleIn(
          initialScale = 0.75f,
          transformOrigin = TransformOrigin(0.5f, if (below) 0f else 1f),
          animationSpec = spring(dampingRatio = 0.58f, stiffness = 400f),
        ),
    exit =
      fadeOut(tween(120)) +
        scaleOut(
          targetScale = 0.85f,
          transformOrigin = TransformOrigin(0.5f, if (below) 0f else 1f),
          animationSpec = tween(120),
        ),
  ) {
    MarkerEditor(
      marker = marker,
      below = below,
      arrowOffset = -shift,
      onClose = onClose,
      modifier = editorModifier,
    )
  }
}

@Composable
private fun MarkerEditor(
  marker: EditableMarker,
  below: Boolean,
  arrowOffset: Float,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var showColors by remember { mutableStateOf(false) }
  val focusManager = LocalFocusManager.current

  fun closeEditor() {
    onClose()
    focusManager.clearFocus()
  }

  var width by remember { mutableStateOf(272f) }
  val density = LocalDensity.current
  val direction = LocalLayoutDirection.current
  // The shape's bias runs between the rounded corners, and reverses in RTL.
  val arrowTravel = (width - 2 * (16f + 10f)).coerceAtLeast(1f)
  val bias = (0.5f + arrowOffset / arrowTravel).coerceIn(0f, 1f)
  val shape =
    SpeechBubbleShape(
      cornerRadius = 16.dp,
      arrowSize = 10.dp,
      arrowDirection =
        if (below) SpeechBubbleArrowDirection.Top else SpeechBubbleArrowDirection.Bottom,
      arrowPlacementBias = if (direction == LayoutDirection.Rtl) 1f - bias else bias,
    )

  // Edits are live; closing only dismisses the controls and keyboard.
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    modifier = modifier.onSizeChanged { width = with(density) { it.width.toDp().value } },
    shape = shape,
    shadowElevation = 4.dp,
  ) {
    Column(Modifier.padding(shape.contentPadding)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        MarkerNameField(marker, onClose = ::closeEditor, modifier = Modifier.weight(1f))

        IconButton(onClick = { showColors = !showColors }) {
          Box(
            Modifier.size(24.dp)
              .background(markerColorScheme(marker.color).primary, CircleShape)
              .semantics {
                contentDescription = "Change pin color"
              }
          )
        }
        IconButton(onClick = ::closeEditor) {
          Icon(painterResource(Res.drawable.check_24px), "Done editing")
        }
      }

      // Color choices stay out of the way until requested.
      AnimatedVisibility(showColors) {
        MarkerColorPicker(marker, onSelected = { showColors = false })
      }
    }
  }
}

@Composable
private fun MarkerNameField(
  marker: EditableMarker,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  BasicTextField(
    value = marker.label,
    onValueChange = { marker.label = it.take(60) },
    singleLine = true,
    textStyle =
      MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    keyboardActions = KeyboardActions(onDone = { onClose() }),
    modifier =
      modifier
        .padding(start = 16.dp, top = 16.dp, bottom = 16.dp)
        .semantics { contentDescription = "Place name" }
        .onPreviewKeyEvent {
          if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
            onClose()
            true
          } else false
        },
    decorationBox = { field ->
      Box {
        if (marker.label.isEmpty())
          Text("Place name", color = MaterialTheme.colorScheme.onSurfaceVariant)
        field()
      }
    },
  )
}

@Composable
private fun MarkerColorPicker(marker: EditableMarker, onSelected: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 12.dp).selectableGroup(),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    for (choice in MarkerColor.entries) {
      val swatch = markerColorScheme(choice)
      Box(
        Modifier.size(48.dp)
          .clip(CircleShape)
          .selectable(
            selected = marker.color == choice,
            role = Role.RadioButton,
            onClick = {
              marker.color = choice
              marker.bounce++
              onSelected()
            },
          )
          .semantics { contentDescription = "${choice.label} pin" },
        contentAlignment = Alignment.Center,
      ) {
        Box(
          Modifier.size(32.dp).background(swatch.primary, CircleShape),
          contentAlignment = Alignment.Center,
        ) {
          if (marker.color == choice)
            Icon(
              painterResource(Res.drawable.check_24px),
              null,
              tint = swatch.onPrimary,
              modifier = Modifier.size(20.dp),
            )
        }
      }
    }
  }
}
