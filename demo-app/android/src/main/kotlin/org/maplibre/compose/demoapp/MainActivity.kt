package org.maplibre.compose.demoapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.maplibre.compose.benchmark.recordBenchmarkOutput

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // A benchmark run's output goes to a file the runner reads; logcat drops lines.
    if (intent.getStringExtra("benchmark") != null) recordBenchmarkOutput(this)
    enableEdgeToEdge()
    val launch = DemoLaunch.parse { intent.getStringExtra(it) }
    setContent { DemoApp(launch) }
  }
}
