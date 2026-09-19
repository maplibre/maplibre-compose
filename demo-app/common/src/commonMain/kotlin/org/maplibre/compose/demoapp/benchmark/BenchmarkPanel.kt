package org.maplibre.compose.demoapp

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.maplibre.compose.demoapp.benchmark.BenchmarkConfig
import org.maplibre.compose.demoapp.benchmark.BenchmarkScenario
import org.maplibre.compose.demoapp.benchmark.allBenchmarkScenarios
import org.maplibre.compose.demoapp.benchmark.defaultParamsJson

@Composable
internal fun BenchmarksScreen(onBack: () -> Unit, onOpenScenario: (BenchmarkScenario) -> Unit) {
  SettingsSubScreen("Benchmarks", onBack) {
    allBenchmarkScenarios.forEach { scenario ->
      SubmenuRow(scenario.title, scenario.description) { onOpenScenario(scenario) }
    }
  }
}

@Composable
internal fun BenchmarkScenarioPanel(state: DemoAppState, onRun: (BenchmarkConfig) -> Unit) {
  val ui = state.benchmark
  val scenario = state.selectedScenario
  LaunchedEffect(scenario) { ui.paramsJson = scenario.defaultParamsJson }
  Text(scenario.description, Modifier.padding(16.dp))
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
  OutlinedTextField(
    value = ui.paramsJson,
    onValueChange = { ui.paramsJson = it },
    label = { Text("Params (JSON)") },
    enabled = !ui.running,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
  )
  Text(ui.status, Modifier.padding(16.dp))
  Button(
    onClick = {
      val config =
        try {
          BenchmarkConfig.of(scenario, ui.surface, ui.maximumFps, ui.load, ui.paramsJson)
        } catch (e: Exception) {
          ui.status = "Invalid params: ${e.message}"
          null
        }
      if (config != null) onRun(config)
    },
    enabled = !ui.running,
  ) {
    Text("Run")
  }
  if (ui.running) Button(onClick = ui::abandonRun) { Text("Cancel") }
  Text(
    "Use the benchmark capture runner for visual alignment, input response, and platform performance measurements.",
    Modifier.padding(16.dp),
  )
}
