package dev.sargunv.maplibrecompose.demoapp.ui.pages

import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import dev.sargunv.maplibrecompose.compose.CameraState
import dev.sargunv.maplibrecompose.compose.StyleState
import dev.sargunv.maplibrecompose.demoapp.StyleInfo
import kotlinx.serialization.Serializable

object Routes {
  @Serializable object MainMenu

  @Serializable object StyleSelector

  @Serializable object OfflineManager

  @Serializable object MapOptions

  data class Context(
    val modifier: Modifier,
    val nav: NavHostController,
    val cameraState: CameraState,
    val styleState: StyleState,
    val selectedStyle: StyleInfo,
    val onStyleSelected: (StyleInfo) -> Unit,
  )
}
