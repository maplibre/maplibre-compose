package org.maplibre.compose.demoapp

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.maplibre.compose.demoapp.benchmark.BenchmarkReport
import org.maplibre.compose.demoapp.benchmark.BenchmarkScenario
import org.maplibre.compose.demoapp.benchmark.allBenchmarkScenarios
import org.maplibre.compose.demoapp.design.SectionHeader

@Composable
internal fun BenchmarksScreen(
  onBack: () -> Unit,
  onOpenScenario: (BenchmarkScenario) -> Unit,
) {
  SettingsSubScreen("Benchmarks", onBack) {
    allBenchmarkScenarios.forEach { scenario ->
      SubmenuRow(scenario.title, scenario.description) { onOpenScenario(scenario) }
    }
  }
}

@Composable
internal fun BenchmarkScenarioPanel(state: DemoAppState) {
  val scenario = state.selectedScenario
  val ui = state.benchmark
  Text(
    text = scenario.description,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 16.dp),
  )
  Text(
    text = ui.status,
    style = MaterialTheme.typography.bodyMedium,
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
  )
  Button(
    onClick = { ui.requestRun() },
    enabled = !ui.running,
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
    Text(if (ui.running) "Running" else "Run")
  }
  ui.report?.let { report ->
    SectionHeader("Last run")
    Text(
      text = formatReport(report),
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.padding(horizontal = 16.dp),
    )
  }
}

private fun formatReport(report: BenchmarkReport): String {
  val frames = report.frames
  val gesture = report.gesture
  val frameLines =
    "avg ${frames.avgMs.fmt()} ms · p95 ${frames.p95Ms.fmt()} ms · max ${frames.maxMs.fmt()} ms\n" +
      "${frames.frames} frames · ${frames.droppedFrames} dropped · vsync ${frames.vsyncMs.fmt()} ms\n" +
      "prefetch ${report.prefetch} · ${report.durationMs.fmt()} ms · ${report.platform}"
  if (gesture == null) return frameLines
  return frameLines +
    "\ntrail p50 ${gesture.medianTrailPx.fmt()} px · p95 ${gesture.p95TrailPx.fmt()} px\n" +
    "latency p50 ${gesture.medianLatencyMs.fmt()} ms · p95 ${gesture.p95LatencyMs.fmt()} ms"
}

private fun Double.fmt(): String {
  val scaled = (this * 10.0).toLong()
  val whole = scaled / 10
  val frac = kotlin.math.abs(scaled % 10)
  return "$whole.$frac"
}
