package org.maplibre.compose.demoapp

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.maplibre.compose.demoapp.benchmark.BenchmarkScenario
import org.maplibre.compose.demoapp.benchmark.allBenchmarkScenarios

@Composable
internal fun BenchmarksScreen(onBack: () -> Unit, onOpenScenario: (BenchmarkScenario) -> Unit) {
  SettingsSubScreen("Benchmarks", onBack) {
    allBenchmarkScenarios.forEach { scenario ->
      SubmenuRow(scenario.title, scenario.description) { onOpenScenario(scenario) }
    }
  }
}

@Composable
internal fun BenchmarkScenarioPanel(state: DemoAppState, onRun: () -> Unit) {
  val ui = state.benchmark
  Text(state.selectedScenario.description, Modifier.padding(16.dp))
  Button(
    onClick = {
      ui.maximumFps =
        when (ui.maximumFps) {
          null -> 30
          30 -> 60
          60 -> 120
          else -> null
        }
    },
    enabled = !ui.running,
  ) {
    Text("Map FPS: ${ui.maximumFps ?: "default"}")
  }
  Button(
    onClick = { ui.surface = if (ui.surface == "surface") "texture" else "surface" },
    enabled = !ui.running,
  ) {
    Text("Android presentation: ${ui.surface}")
  }
  Button(onClick = { ui.load = if (ui.load == 0) 5000 else 0 }, enabled = !ui.running) {
    Text("Additional circles: ${ui.load}")
  }
  Text(ui.status, Modifier.padding(16.dp))
  Button(onClick = onRun, enabled = !ui.running) { Text("Run") }
  if (ui.running) Button(onClick = ui::abandonRun) { Text("Cancel") }
  Text(
    "Use the benchmark capture runner for visual alignment, input response, and platform performance measurements.",
    Modifier.padding(16.dp),
  )
}
