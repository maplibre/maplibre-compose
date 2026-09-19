package org.maplibre.compose.demoapp.demos.featureediting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.DemoDestination
import org.maplibre.compose.demoapp.design.ButtonRow
import org.maplibre.compose.demoapp.design.SegmentedRow
import org.maplibre.compose.demoapp.design.SliderRow
import org.maplibre.compose.demoapp.design.SwitchRow
import org.maplibre.compose.demoapp.flyTo
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.demoapp.generated.arrow_selector_tool_24px
import org.maplibre.compose.demoapp.generated.circle_24px
import org.maplibre.compose.demoapp.generated.delete_24px
import org.maplibre.compose.demoapp.generated.pentagon_24px
import org.maplibre.compose.demoapp.generated.polyline_24px
import org.maplibre.compose.demoapp.generated.undo_24px
import org.maplibre.compose.editing.EditorFeature
import org.maplibre.compose.editing.EditorTool
import org.maplibre.compose.editing.contains
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.turf.measurement.area
import org.maplibre.spatialk.turf.measurement.computeBbox
import org.maplibre.spatialk.turf.measurement.length
import org.maplibre.spatialk.units.extensions.meters
import org.maplibre.spatialk.units.extensions.squareMeters

/** The tool row: Select, Polygon, Line, Circle and Undo. */
@Composable
internal fun ToolRow(state: FeatureEditingState) {
  val editor = state.editor
  val tooltips = state.lastPointerType != PointerType.Touch
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val tools =
      listOf(
        ToolChoice(state.selectTool, "Select", "Select (1)", Res.drawable.arrow_selector_tool_24px),
        ToolChoice(state.polygonTool, "Polygon", "Polygon (2)", Res.drawable.pentagon_24px),
        ToolChoice(state.lineTool, "Line", "Line (3)", Res.drawable.polyline_24px),
        ToolChoice(state.circleTool, "Circle", "Circle (4)", Res.drawable.circle_24px),
      )
    SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
      tools.forEachIndexed { index, choice ->
        WithTooltip(choice.tooltip, tooltips, Modifier.weight(1f)) {
          SegmentedButton(
            selected = editor.tool === choice.tool,
            onClick = { state.use(choice.tool) },
            shape = SegmentedButtonDefaults.itemShape(index = index, count = tools.size),
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
            icon = {},
            label = { Icon(painterResource(choice.icon), choice.name, Modifier.size(20.dp)) },
          )
        }
      }
    }
    Spacer(Modifier.width(4.dp))
    WithTooltip(undoShortcutHint?.let { "Undo ($it)" } ?: "Undo", tooltips) {
      IconButton(
        enabled = editor.canUndo,
        onClick = {
          editor.undo()
          state.mapFocus.requestFocus()
        },
      ) {
        Icon(painterResource(Res.drawable.undo_24px), "Undo")
      }
    }
  }
}

private class ToolChoice(
  val tool: EditorTool,
  val name: String,
  val tooltip: String,
  val icon: DrawableResource,
)

@Composable
private fun WithTooltip(
  text: String,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  if (!enabled) {
    Box(modifier) { content() }
    return
  }
  TooltipBox(
    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
    tooltip = { PlainTooltip { Text(text) } },
    state = rememberTooltipState(),
    modifier = modifier,
    content = content,
  )
}

/** The measurements of the selection and the unit switch. */
@Composable
internal fun MeasurementCard(state: FeatureEditingState) {
  val selected = state.selected
  val units = state.units
  val single = selected.singleOrNull()
  when {
    selected.isEmpty() ->
      Text(
        "Select a shape to measure it",
        Modifier.padding(16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    single != null -> {
      val measure = remember(single.geometry) { ShapeMeasure.of(single) }
      when (measure.kind) {
        ShapeKind.Polygon -> {
          MeasureRow("Area", units.area(measure.area))
          MeasureRow("Perimeter", units.length(measure.length))
          MeasureRow(
            "Extent",
            "${units.length(measure.extentWidth)} × ${units.length(measure.extentHeight)}",
          )
          MeasureRow("Corners", measure.corners.toString())
          MeasureRow("Centre", units.coordinate(measure.center))
        }
        ShapeKind.Line -> {
          MeasureRow("Length", units.length(measure.length))
          MeasureRow("Straight line", measure.straight?.let(units::length) ?: "–")
          MeasureRow("Bearing", measure.bearing?.let(units::bearing) ?: "–")
          MeasureRow(
            "Extent",
            "${units.length(measure.extentWidth)} × ${units.length(measure.extentHeight)}",
          )
          MeasureRow("Points", measure.corners.toString())
        }
        ShapeKind.Circle -> {
          MeasureRow("Radius", units.length(measure.radius ?: 0.meters))
          MeasureRow("Area", units.area(measure.area))
          MeasureRow("Circumference", units.length(measure.length))
          MeasureRow("Centre", units.coordinate(measure.center))
        }
      }
    }
    else -> {
      MeasureRow("Shapes", selected.size.toString())
      val totalArea =
        selected
          .filter { it.kind != ShapeKind.Line }
          .fold(0.0.squareMeters) { acc, f -> acc + f.geometry.area() }
      val totalLength =
        selected
          .filter { it.kind == ShapeKind.Line }
          .fold(0.meters) { acc, f -> acc + f.geometry.length() }
      MeasureRow("Total area", units.area(totalArea))
      MeasureRow("Total length", units.length(totalLength))
    }
  }
  SegmentedRow(
    label = "Units",
    options = MeasureUnits.entries,
    selected = units,
    optionLabel = { it.label },
    onSelect = {
      state.units = it
      state.mapFocus.requestFocus()
    },
  )
}

@Composable
private fun MeasureRow(label: String, value: String) {
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, style = MaterialTheme.typography.bodyLarge)
    Text(
      value,
      style = MaterialTheme.typography.bodyMedium.tabular,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** Simplify, snapping, fit, remove and redo. */
@Composable
internal fun ShapeSection(state: FeatureEditingState, appState: DemoAppState) {
  val editor = state.editor
  val scope = rememberCoroutineScope()
  val selected = state.selected
  val scrub = state.simplifyScrub
  // The slider follows the finger while scrubbing and eases back to zero after the commit.
  val settled by
    animateFloatAsState(
      scrub?.value ?: 0f,
      if (scrub != null) snap() else tween(250),
      label = "simplify",
    )
  val sliderValue = scrub?.value ?: settled
  SliderRow(
    label = "Simplify",
    value = sliderValue,
    range = 0f..1f,
    valueLabel = { simplifyLabel(state, scrub) },
    onChange = state::scrubSimplify,
    enabled = state.simplifiable != null,
    onChangeFinished = {
      state.finishSimplify()
      state.mapFocus.requestFocus()
    },
  )
  SwitchRow("Snap to corners", state.snapping) {
    state.snapping = it
    state.mapFocus.requestFocus()
  }
  if (state.lastPointerType != PointerType.Touch) {
    Text(
      "Hold Alt to snap off while dragging",
      Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
  ButtonRow("Fit to selection", enabled = selected.isNotEmpty()) {
    selected
      .map { it.geometry }
      .unionBbox()
      ?.let { bbox ->
        scope.launch {
          appState.mapState.flyTo(
            DemoDestination.FitBounds(bbox, EditingFitPadding),
            appState.settings.flightAnimation,
          )
        }
      }
    state.mapFocus.requestFocus()
  }
  ButtonRow(
    if (selected.size > 1) "Remove shapes" else "Remove shape",
    enabled = selected.isNotEmpty(),
  ) {
    state.removeSelection()
  }
  ButtonRow("Redo", enabled = editor.canRedo) {
    editor.redo()
    state.mapFocus.requestFocus()
  }
}

private fun simplifyLabel(state: FeatureEditingState, scrub: SimplifyScrub?): String {
  if (scrub == null) return "Drag to drop corners"
  val original = distinctPositions(scrub.original.geometry)
  val current = state.editor.feature(scrub.id)?.let { distinctPositions(it.geometry) } ?: original
  val word = if (scrub.original.kind == ShapeKind.Line) "points" else "corners"
  val tolerance = simplifyTolerance(scrub.original.geometry, scrub.value)
  val center = scrub.original.geometry.computeBbox()
  val meters = degreesToMeters(tolerance, (center.south + center.north) / 2)
  return "$original → $current $word · ≈ ${state.units.length(meters)}"
}

/** One row per shape, with an empty state once the last one is removed. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ShapesList(state: FeatureEditingState, appState: DemoAppState) {
  val editor = state.editor
  val scope = rememberCoroutineScope()
  val entries = rememberLaggingEntries(editor.features, editor.selection)
  Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
    for (entry in entries) {
      key(entry.id) {
        AnimatedVisibility(
          visibleState = entry.visible,
          enter = expandVertically() + fadeIn(),
          exit = shrinkVertically(tween(180)) + fadeOut(tween(180)),
        ) {
          ShapeRow(
            feature = editor.feature(entry.id) ?: entry.feature,
            units = state.units,
            selected = entry.selected,
            onSelect = {
              state.select(entry.id)
              val bbox = entry.feature.geometry.computeBbox()
              if (!bbox.isOnScreen(appState)) {
                scope.launch {
                  appState.mapState.flyTo(
                    DemoDestination.FitBounds(bbox, EditingFitPadding),
                    appState.settings.flightAnimation,
                  )
                }
              }
            },
            onRemove = { state.remove(entry.id) },
          )
        }
      }
    }
    AnimatedVisibility(editor.features.isEmpty()) { EmptyShapes(state, appState) }
  }
}

private fun BoundingBox.isOnScreen(appState: DemoAppState): Boolean {
  val visible = appState.mapState.getVisibleBounds() ?: return true
  val bounds =
    BoundingBox(
      visible.southwest.longitude,
      visible.southwest.latitude,
      visible.northeast.longitude,
      visible.northeast.latitude,
    )
  return listOf(
      Position(west, south),
      Position(east, south),
      Position(east, north),
      Position(west, north),
    )
    .all { it in bounds }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ShapeRow(
  feature: EditorFeature,
  units: MeasureUnits,
  selected: Boolean,
  onSelect: () -> Unit,
  onRemove: () -> Unit,
) {
  val dismiss = rememberSwipeToDismissBoxState()
  val measure = remember(feature.geometry) { ShapeMeasure.of(feature) }
  val name = feature.displayName
  SwipeToDismissBox(
    state = dismiss,
    modifier = Modifier.padding(top = 8.dp).clip(MaterialTheme.shapes.medium),
    enableDismissFromStartToEnd = false,
    onDismiss = { onRemove() },
    backgroundContent = {
      Row(
        Modifier.fillMaxSize()
          .background(
            if (dismiss.dismissDirection == SwipeToDismissBoxValue.Settled) Color.Transparent
            else MaterialTheme.colorScheme.errorContainer
          )
          .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          painterResource(Res.drawable.delete_24px),
          null,
          tint = MaterialTheme.colorScheme.onErrorContainer,
        )
      }
    },
  ) {
    ListItem(
      selected = selected,
      onClick = onSelect,
      content = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
      supportingContent = {
        Text(
          when (measure.kind) {
            ShapeKind.Circle -> "r ${units.length(measure.radius ?: 0.meters)}"
            else -> units.primary(measure.kind, measure.primaryMeters)
          },
          style = MaterialTheme.typography.bodyMedium.tabular,
        )
      },
      leadingContent = {
        Icon(
          painterResource(
            when (measure.kind) {
              ShapeKind.Polygon -> Res.drawable.pentagon_24px
              ShapeKind.Line -> Res.drawable.polyline_24px
              ShapeKind.Circle -> Res.drawable.circle_24px
            }
          ),
          null,
          tint =
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      },
      trailingContent = {
        IconButton(onClick = onRemove) {
          Icon(painterResource(Res.drawable.delete_24px), "Remove $name")
        }
      },
      colors =
        ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
  }
}

@Composable
private fun EmptyShapes(state: FeatureEditingState, appState: DemoAppState) {
  val scope = rememberCoroutineScope()
  Column(
    Modifier.fillMaxWidth().padding(vertical = 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(
      painterResource(Res.drawable.pentagon_24px),
      null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(48.dp),
    )
    Spacer(Modifier.height(12.dp))
    Text("No shapes yet", style = MaterialTheme.typography.titleMedium)
    Text(
      "Draw one with the tools above, or start from a preset.",
      Modifier.padding(top = 4.dp),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    FilledTonalButton(
      onClick = {
        val bbox = state.loadAllPresets()
        scope.launch {
          appState.mapState.flyTo(
            DemoDestination.FitBounds(bbox, EditingFitPadding),
            appState.settings.flightAnimation,
          )
        }
      }
    ) {
      Text("Load the presets")
    }
  }
}

/** One row per preset. */
@Composable
internal fun PresetsSection(state: FeatureEditingState, appState: DemoAppState) {
  val scope = rememberCoroutineScope()
  for (preset in Presets.all) {
    ButtonRow(preset.displayName) {
      val bbox = state.loadPreset(preset)
      scope.launch {
        appState.mapState.flyTo(
          DemoDestination.FitBounds(bbox, EditingFitPadding),
          appState.settings.flightAnimation,
        )
      }
    }
  }
}
