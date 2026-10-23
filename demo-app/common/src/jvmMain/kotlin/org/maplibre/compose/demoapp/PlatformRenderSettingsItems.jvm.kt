package org.maplibre.compose.demoapp

import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import org.maplibre.compose.desktop.LocalComposeMapPresentationHost
import org.maplibre.nativeffi.Maplibre
import org.maplibre.nativeffi.generated.GeneratedApi
import org.maplibre.nativeffi.generated.RenderBackendFlag

@Composable
actual fun PlatformRenderSettingsItems(settings: DemoSettings) {
  val mapBackends = remember {
    runCatching {
        Maplibre.loadNativeLibrary()
        val supported = GeneratedApi.supportedRenderBackendMask()
        listOf(
            RenderBackendFlag.METAL to "Metal",
            RenderBackendFlag.VULKAN to "Vulkan",
            RenderBackendFlag.OPENGL to "OpenGL",
            RenderBackendFlag.WEBGPU to "WebGPU",
          )
          .filter { (flag, _) -> flag in supported }
          .joinToString(" / ") { (_, name) -> name }
      }
      .getOrDefault("unavailable")
  }
  val host = LocalComposeMapPresentationHost.current
  ListItem(
    headlineContent = { Text("Rendering bridge") },
    supportingContent = { Text("MapLibre $mapBackends → ${host.description}") },
    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
  )
}
