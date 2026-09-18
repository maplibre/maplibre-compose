package org.maplibre.compose.demoapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val launch = DemoLaunch.parse { intent.getStringExtra(it) }
    setContent { DemoApp(launch) }
  }
}
