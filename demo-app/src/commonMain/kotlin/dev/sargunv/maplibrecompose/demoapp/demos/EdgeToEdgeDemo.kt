package dev.sargunv.maplibrecompose.demoapp.demos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.sargunv.maplibrecompose.compose.MaplibreMap
import dev.sargunv.maplibrecompose.compose.rememberCameraState
import dev.sargunv.maplibrecompose.compose.rememberStyleState
import dev.sargunv.maplibrecompose.core.CameraPosition
import dev.sargunv.maplibrecompose.demoapp.Demo
import dev.sargunv.maplibrecompose.demoapp.DemoAppBar
import dev.sargunv.maplibrecompose.demoapp.DemoMapControls
import dev.sargunv.maplibrecompose.demoapp.DemoOrnamentSettings
import dev.sargunv.maplibrecompose.demoapp.EMPTY_STYLE
import dev.sargunv.maplibrecompose.demoapp.getDefaultColorScheme
import dev.sargunv.maplibrecompose.material3.style.MapColorScheme
import dev.sargunv.maplibrecompose.material3.style.MaterialMapStyle
import io.github.dellisd.spatialk.geojson.Position

private val PORTLAND = Position(latitude = 45.521, longitude = -122.675)

object EdgeToEdgeDemo : Demo {
  override val name = "Edge-to-edge"
  override val description =
    "Fill the entire screen with a map and pad ornaments to position them correctly."

  @Composable
  override fun Component(navigateUp: () -> Unit) {
    val cameraState = rememberCameraState(CameraPosition(target = PORTLAND, zoom = 13.0))
    val styleState = rememberStyleState()
    val colorScheme = getDefaultColorScheme()
    val mapColorScheme = remember(colorScheme) { MapColorScheme(colorScheme) }

    MaterialTheme(colorScheme = colorScheme) {
      Scaffold(topBar = { DemoAppBar(this, navigateUp, alpha = 0.5f) }) { padding ->
        Box(modifier = Modifier.consumeWindowInsets(WindowInsets.safeContent).fillMaxSize()) {
          MaplibreMap(
            styleUri = EMPTY_STYLE,
            cameraState = cameraState,
            styleState = styleState,
            ornamentSettings = DemoOrnamentSettings(padding),
          ) {
            MaterialMapStyle(colorScheme = mapColorScheme)
          }
          DemoMapControls(cameraState, styleState, modifier = Modifier.padding(padding))
        }
      }
    }
  }
}
