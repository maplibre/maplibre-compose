package dev.sargunv.maplibrecompose.demoapp.demos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.sargunv.maplibrecompose.compose.MaplibreMap
import dev.sargunv.maplibrecompose.compose.rememberCameraState
import dev.sargunv.maplibrecompose.compose.rememberStyleState
import dev.sargunv.maplibrecompose.core.RenderOptions
import dev.sargunv.maplibrecompose.demoapp.DEFAULT_STYLE
import dev.sargunv.maplibrecompose.demoapp.Demo
import dev.sargunv.maplibrecompose.demoapp.DemoMapControls
import dev.sargunv.maplibrecompose.demoapp.DemoMapOptions
import dev.sargunv.maplibrecompose.demoapp.DemoScaffold
import org.jetbrains.compose.resources.ExperimentalResourceApi

object TextureModeDemo : Demo {
  override val name = "Texture Mode"
  override val description = "Render the map to a TextureView instead of a GLSurfaceView."

  @Composable
  @OptIn(ExperimentalResourceApi::class)
  override fun Component(navigateUp: () -> Unit) {
    DemoScaffold(this, navigateUp) {
      val cameraState = rememberCameraState()
      val styleState = rememberStyleState()

      Box(modifier = Modifier.Companion.fillMaxSize()) {
        MaplibreMap(
          baseStyle = DEFAULT_STYLE,
          cameraState = cameraState,
          styleState = styleState,
          options =
            DemoMapOptions()
              .copy(
                renderOptions = RenderOptions(renderMode = RenderOptions.RenderMode.TextureView)
              ),
        )
        DemoMapControls(cameraState, styleState)
      }
    }
  }
}
