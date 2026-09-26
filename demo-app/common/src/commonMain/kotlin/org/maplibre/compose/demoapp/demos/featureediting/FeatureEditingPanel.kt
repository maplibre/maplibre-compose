package org.maplibre.compose.demoapp.demos.featureediting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.DemoDestination
import org.maplibre.compose.demoapp.design.ButtonRow
import org.maplibre.compose.demoapp.design.SectionHeader
import org.maplibre.compose.demoapp.flyTo
import org.maplibre.spatialk.turf.measurement.area
import org.maplibre.spatialk.turf.measurement.computeBbox
import org.maplibre.spatialk.turf.measurement.length
import org.maplibre.spatialk.units.extensions.inHectares
import org.maplibre.spatialk.units.extensions.inMeters

@Composable
internal fun EditingActions(editor: FeatureEditingState) {
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    if (editor.draft != null) {
      Button(onClick = { editor.accept() }, enabled = editor.displayed.problem() == null) {
        Text("Done")
      }
      TextButton(onClick = editor::back, enabled = editor.displayed.vertices.isNotEmpty()) {
        Text("Back")
      }
      TextButton(onClick = editor::cancel) { Text("Cancel") }
    } else {
      FilledTonalButton(onClick = { editor.draw(ShapeKind.Polygon) }, enabled = !editor.busy) {
        Text("Polygon")
      }
      FilledTonalButton(onClick = { editor.draw(ShapeKind.Line) }, enabled = !editor.busy) {
        Text("Line")
      }
      TextButton(onClick = editor::undo, enabled = editor.canUndo) { Text("Undo") }
    }
  }
}

@Composable
internal fun EditingPanel(editor: FeatureEditingState, app: DemoAppState) {
  val shown = editor.displayed
  val geometry = shown.geometry
  val problem = editor.message ?: shown.problem()
  val scope = rememberCoroutineScope()
  fun fit() {
    geometry?.let {
      scope.launch {
        app.mapState.flyTo(
          DemoDestination.FitBounds(it.computeBbox()),
          app.settings.flightAnimation,
        )
      }
    }
  }
  Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      if (editor.draft != null) "Tap the map to place vertices, then choose Done."
      else "Drag a vertex to reshape. Tap one to insert or remove a point.",
      style = MaterialTheme.typography.bodyMedium,
    )
    if (problem != null) Text(problem, color = MaterialTheme.colorScheme.error)
    Text("${shown.vertices.size} vertices", style = MaterialTheme.typography.labelLarge)
    if (geometry != null && shown.problem() == null) {
      val length = geometry.length().inMeters.roundToInt()
      if (shown.kind == ShapeKind.Polygon) {
        val hectares = (geometry.area().inHectares * 10).roundToInt() / 10.0
        Text("$hectares ha", style = MaterialTheme.typography.headlineMedium)
        Text("Perimeter · $length m", style = MaterialTheme.typography.bodyMedium)
      } else Text("$length m", style = MaterialTheme.typography.headlineMedium)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      TextButton(onClick = ::fit, enabled = geometry != null) { Text("Fit shape") }
      TextButton(onClick = editor::redo, enabled = editor.canRedo) { Text("Redo") }
    }
  }
  val index = editor.selectedVertex
  if (index != null && !editor.busy) {
    SectionHeader("Vertex ${index + 1}")
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      val next =
        if (index == shown.vertices.lastIndex && shown.kind == ShapeKind.Polygon) 0 else index + 1
      OutlinedButton(
        onClick = {
          val a = app.mapState.screenLocationFromPosition(shown.vertices[index])
          val b = shown.vertices.getOrNull(next)?.let(app.mapState::screenLocationFromPosition)
          if (a != null && b != null)
            app.mapState
              .positionFromScreenLocation(DpOffset((a.x + b.x) / 2, (a.y + b.y) / 2))
              ?.let(editor::insertVertex)
        },
        enabled = next < shown.vertices.size,
      ) {
        Text("Insert after")
      }
      TextButton(
        onClick = editor::removeVertex,
        enabled = shown.vertices.size > shown.kind.minimum,
      ) {
        Text("Remove")
      }
    }
  }
  SectionHeader("Simplify")
  var amount by remember(editor.shape, editor.draft) { mutableFloatStateOf(0f) }
  Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      "Reduce the vertex count. The faint outline shows the original shape.",
      style = MaterialTheme.typography.bodyMedium,
    )
    Slider(
      value = if (editor.simplifying) amount else 0f,
      onValueChange = {
        amount = it
        editor.simplify(it)
      },
      enabled = !editor.busy || editor.simplifying,
    )
    if (editor.simplifying) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { editor.accept() }, enabled = shown.problem() == null) { Text("Apply") }
        TextButton(onClick = editor::cancel) { Text("Cancel") }
      }
    }
    OutlinedButton(onClick = { editor.replace(editor.shape.rotated()) }, enabled = !editor.busy) {
      Text("Rotate 15°")
    }
  }
  SectionHeader("Try a shape")
  for (preset in listOf(Presets.goldenGatePark, Presets.jfkDrive, Presets.conservatoryCircle)) {
    ButtonRow(preset.properties.getValue("name").toString().trim('"')) {
      editor.cancel()
      editor.replace(Shape.of(preset.geometry))
      scope.launch {
        app.mapState.flyTo(
          DemoDestination.FitBounds(preset.geometry.computeBbox()),
          app.settings.flightAnimation,
        )
      }
    }
  }
}
