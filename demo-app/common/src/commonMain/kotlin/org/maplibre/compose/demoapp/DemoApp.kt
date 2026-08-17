package org.maplibre.compose.demoapp

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.maplibre.compose.map.MaplibreMap

@Composable
fun DemoApp() {
  MaplibreMap(modifier = Modifier.fillMaxSize())
}
