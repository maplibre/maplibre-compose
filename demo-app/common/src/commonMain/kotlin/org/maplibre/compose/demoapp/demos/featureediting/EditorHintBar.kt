package org.maplibre.compose.demoapp.demos.featureediting

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.maplibre.compose.demoapp.controlPadding
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.demoapp.generated.check_24px
import org.maplibre.compose.demoapp.generated.close_24px
import org.maplibre.compose.demoapp.generated.delete_24px
import org.maplibre.compose.editing.HandleKind
import org.maplibre.compose.editing.VertexRef
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Polygon

/** What the hint bar shows: one line of text and up to two buttons. */
private data class Hint(
  val text: String,
  val error: Boolean = false,
  val done: Boolean = false,
  val cancel: Boolean = false,
  /** The vertex a Delete point button removes, when one is active. */
  val vertex: VertexRef? = null,
  val canDeleteVertex: Boolean = false,
)

/** How long the coach hint stays before it fades on its own. */
private const val COACH_MILLIS = 5_000L

/**
 * The pill at the top of the map that says what to do next: validation errors, drawing steps, the
 * active corner, a first-run coach line, or the empty state.
 */
@Composable
internal fun EditorHintBar(state: FeatureEditingState) {
  val editor = state.editor
  val mouse = state.lastPointerType == PointerType.Mouse
  val error = editor.validationError
  var lingeringError by remember { mutableStateOf<String?>(null) }
  // Keyed on the message alone: an error a vertex tooltip is already showing stays there.
  LaunchedEffect(error) {
    if (error != null && editor.activeHandle == null) lingeringError = error
    else if (lingeringError != null) {
      delay(ERROR_LINGER_MILLIS)
      lingeringError = null
    }
  }
  val coach =
    !state.coachDismissed && editor.tool === state.selectTool && editor.selection.isNotEmpty()
  LaunchedEffect(coach) {
    if (coach) {
      delay(COACH_MILLIS)
      state.coachDismissed = true
    }
  }
  LaunchedEffect(editor.gestureInProgress, editor.tool) {
    if (coach && (editor.gestureInProgress || editor.tool !== state.selectTool)) {
      state.coachDismissed = true
    }
  }
  val draft = editor.draft
  val hasDraft = draft != null
  val placed = draft?.positions?.size ?: 0
  // A pressed midpoint is active until its drag inserts a vertex; it has no corner to name.
  val activeVertex = editor.activeHandle?.takeIf { it.kind == HandleKind.Vertex }?.vertex
  val hint: Hint? =
    when {
      lingeringError != null ->
        Hint(checkNotNull(lingeringError), error = true, done = hasDraft, cancel = hasDraft)
      editor.tool === state.polygonTool ->
        Hint(
          when {
            placed == 0 ->
              if (mouse) "Click to place the first corner" else "Tap to place the first corner"
            placed < 3 -> if (mouse) "Click to add corners" else "Tap to add corners"
            else -> if (mouse) "Double-click or Enter to close" else "Tap the first corner to close"
          },
          done = true,
          cancel = true,
        )
      editor.tool === state.lineTool ->
        Hint(
          when {
            placed == 0 ->
              if (mouse) "Click to place the first point" else "Tap to place the first point"
            placed < 2 -> if (mouse) "Click to add points" else "Tap to add points"
            else -> if (mouse) "Double-click or Enter to finish" else "Tap the last point to finish"
          },
          done = true,
          cancel = true,
        )
      editor.tool === state.circleTool ->
        if (!hasDraft) {
          Hint(
            if (mouse) "Click the centre, or drag out a circle" else "Tap the centre",
            cancel = true,
          )
        } else if (mouse) {
          Hint("Click to set the radius", cancel = true)
        } else {
          Hint("Drag or tap to set the radius", done = true, cancel = true)
        }
      editor.tool === state.selectTool && activeVertex?.featureId != null -> {
        val ref = activeVertex
        val feature = editor.feature(checkNotNull(ref.featureId))
        val geometry = feature?.geometry
        val (word, total) =
          when (geometry) {
            is Polygon ->
              "Corner" to (geometry.coordinates.getOrNull(ref.path[0])?.let { it.size - 1 } ?: 0)
            is LineString -> "Point" to geometry.coordinates.size
            else -> "Point" to 0
          }
        val index = ref.path.last() + 1
        val minimum = if (geometry is Polygon) 3 else 2
        Hint("$word $index of $total", vertex = ref, canDeleteVertex = total > minimum)
      }
      coach -> Hint("Drag a corner or a handle to reshape")
      editor.features.isEmpty() && editor.tool === state.selectTool && !hasDraft ->
        Hint("Choose Polygon, Line or Circle to start")
      else -> null
    }
  // The last hint stays for the exit animation. SideEffect keeps the write out of composition.
  var shown by remember { mutableStateOf(hint) }
  if (hint != null) SideEffect { shown = hint }
  val current = hint ?: shown ?: return
  Box(Modifier.fillMaxSize().controlPadding().padding(horizontal = 56.dp)) {
    AnimatedVisibility(
      visible = hint != null,
      modifier = Modifier.align(Alignment.TopCenter),
      enter =
        fadeIn(tween(120)) +
          scaleIn(initialScale = 0.8f, animationSpec = spring(dampingRatio = 0.6f)),
      exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.8f),
    ) {
      HintPill(state, current, mouse)
    }
  }
}

@Composable
private fun HintPill(state: FeatureEditingState, hint: Hint, mouse: Boolean) {
  val colors = MaterialTheme.colorScheme
  val editor = state.editor
  Surface(
    color = if (hint.error) colors.errorContainer else colors.surfaceContainerHigh,
    contentColor = if (hint.error) colors.onErrorContainer else colors.onSurface,
    shape = CircleShape,
    shadowElevation = 6.dp,
    // A press on the pill must not reach the map under it, where a draw tool would place a vertex.
    modifier = Modifier.widthIn(max = 400.dp).pointerInput(Unit) {},
  ) {
    AnimatedContent(
      targetState = hint,
      transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(160)) },
      label = "hint",
    ) { current ->
      val buttons = current.done || current.cancel || current.vertex != null
      Row(
        Modifier.heightIn(min = 44.dp).padding(start = 16.dp, end = if (buttons) 6.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          current.text,
          Modifier.weight(1f, fill = false).padding(vertical = 8.dp),
          style = MaterialTheme.typography.labelLarge,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
          textAlign = TextAlign.Center,
        )
        if (current.done) {
          HintButton(
            "Done",
            Res.drawable.check_24px,
            mouse,
            enabled = editor.canFinishDraft,
            tonal = true,
          ) {
            editor.finishDraft()
            state.mapFocus.requestFocus()
          }
        }
        if (current.cancel) {
          HintButton("Cancel", Res.drawable.close_24px, mouse) {
            editor.cancelDraft()
            state.use(state.selectTool)
          }
        }
        val vertex = current.vertex
        if (vertex != null) {
          HintButton(
            "Delete point",
            Res.drawable.delete_24px,
            mouse,
            enabled = current.canDeleteVertex,
          ) {
            editor.removeVertex(vertex)
            state.mapFocus.requestFocus()
          }
        }
      }
    }
  }
}

@Composable
private fun HintButton(
  label: String,
  icon: DrawableResource,
  tooltip: Boolean,
  enabled: Boolean = true,
  tonal: Boolean = false,
  onClick: () -> Unit,
) {
  val button: @Composable () -> Unit = {
    if (tonal) {
      FilledTonalIconButton(onClick = onClick, enabled = enabled) {
        Icon(painterResource(icon), label)
      }
    } else {
      IconButton(onClick = onClick, enabled = enabled) { Icon(painterResource(icon), label) }
    }
  }
  if (tooltip) {
    TooltipBox(
      positionProvider =
        TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
      tooltip = { PlainTooltip { Text(label) } },
      state = rememberTooltipState(),
      content = button,
    )
  } else button()
}
