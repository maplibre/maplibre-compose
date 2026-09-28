package org.maplibre.compose.demoapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.maplibre.compose.benchmark.BenchmarkConfig
import org.maplibre.compose.benchmark.BenchmarkScenario
import org.maplibre.compose.benchmark.allBenchmarkScenarios
import org.maplibre.compose.demoapp.benchmark.BenchmarkRun
import org.maplibre.compose.demoapp.benchmark.supportsRuntimeBenchmark

@Composable
internal fun BenchmarksScreen(onBack: () -> Unit, onOpenScenario: (BenchmarkScenario) -> Unit) {
  SettingsSubScreen("Benchmarks", onBack) {
    allBenchmarkScenarios
      .filter { supportsRuntimeBenchmark || it != BenchmarkScenario.RuntimeStartup }
      .forEach { scenario ->
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
    "Use the benchmark runner to save results and compare repeated runs.",
    Modifier.padding(16.dp),
  )
}

class BenchmarkUiState {
  private var nextRunId = 0
  var runId by mutableStateOf(0)
    private set

  var running by mutableStateOf(false)
  var status by mutableStateOf("Ready")
  var configJson by mutableStateOf(BenchmarkConfig().encode())
  var config by mutableStateOf<BenchmarkConfig?>(null)
    private set

  fun requestRun(config: BenchmarkConfig) {
    if (running) return
    running = true
    status = "Starting"
    this.config = config
    runId = ++nextRunId
  }

  fun abandonRun() {
    runId = 0
    running = false
    status = "Ready"
    config = null
  }
}

/** [viewportInsets] keeps the placeholder text out from under the panel. */
@Composable
internal fun BenchmarkMap(state: DemoAppState, viewportInsets: MapViewportInsets) {
  val ui = state.benchmark
  val runId = ui.runId
  val config = ui.config
  Box(Modifier.fillMaxSize().background(Color(0xff202020))) {
    if (config == null || runId == 0)
      Text(
        "Choose settings and run the benchmark.",
        Modifier.padding(viewportInsets.asPaddingValues()).align(Alignment.Center).padding(24.dp),
        color = Color.LightGray,
      )
    else
      key(runId) {
        BenchmarkRun(config) { status, running ->
          if (ui.runId == runId) {
            ui.status = status
            ui.running = running
          }
        }
      }
  }
}
