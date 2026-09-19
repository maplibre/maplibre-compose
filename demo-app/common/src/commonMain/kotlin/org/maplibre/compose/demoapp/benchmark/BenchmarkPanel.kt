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
  LaunchedEffect(scenario) { ui.configJson = BenchmarkConfig.forScenario(scenario).encode() }
  Text(scenario.description, Modifier.padding(16.dp))
  OutlinedTextField(
    value = ui.configJson,
    onValueChange = { ui.configJson = it },
    label = { Text("Benchmark configuration") },
    enabled = !ui.running,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
  )
  Text(ui.status, Modifier.padding(16.dp))
  Button(
    onClick = {
      val config =
        try {
          BenchmarkConfig.parse(ui.configJson)
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
