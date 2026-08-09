package org.maplibre.compose.demoapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.maplibre.compose.android.MapLibre

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    MapLibre.configure(applicationContext)
    enableEdgeToEdge()
    setContent { DemoApp() }
  }
}
