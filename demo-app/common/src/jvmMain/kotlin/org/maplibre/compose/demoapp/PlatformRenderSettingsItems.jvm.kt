package org.maplibre.compose.demoapp

import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import org.maplibre.compose.desktop.LocalComposeMapPresentationHost
import org.maplibre.compose.desktop.OpenGlInterop
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.nativeffi.Maplibre
import org.maplibre.nativeffi.render.RenderBackend

@Composable
actual fun PlatformRenderSettingsItems(settings: DemoSettings) {
  val mapBackends = remember {
    runCatching {
        Maplibre.loadNativeLibrary()
        Maplibre.supportedRenderBackends().joinToString(" / ") {
          when (it) {
            RenderBackend.METAL -> "Metal"
            RenderBackend.VULKAN -> "Vulkan"
            RenderBackend.OPENGL -> "OpenGL"
            RenderBackend.WEBGPU -> "WebGPU"
          }
        }
      }
      .getOrDefault("unavailable")
  }
  val host = LocalComposeMapPresentationHost.current
  val composeBackend =
    when (host.backend) {
      ComposeRenderBackend.METAL -> "Metal"
      ComposeRenderBackend.DIRECT3D12 -> "Direct3D 12"
      ComposeRenderBackend.OPENGL ->
        when (host.openGlInterop) {
          OpenGlInterop.NATIVE -> "OpenGL"
          OpenGlInterop.ANGLE_D3D11 -> "ANGLE / Direct3D 11"
        }
    }
  ListItem(
    headlineContent = { Text("Rendering bridge") },
    supportingContent = { Text("MapLibre $mapBackends → Compose $composeBackend") },
    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
  )
}
