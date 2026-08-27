package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.maplibre.compose.mlnffi.AndroidMapSurfaceKind
import org.maplibre.compose.mlnffi.AndroidMlnFfiSurface
import org.maplibre.compose.mlnffi.EnsureMlnFfiConfigured
import org.maplibre.compose.mlnffi.MapRenderBackend

@Composable
internal actual fun ComposableMapView(state: MapState, modifier: Modifier, options: MapOptions) {
  EnsureMlnFfiConfigured()
  val runtimeBackends = remember { loadRuntimeBackends(state.logger) }
  val renderBackend =
    remember(runtimeBackends) { runtimeBackends.firstOrNull() ?: MapRenderBackend.OPENGL }
  val surfaceKind =
    when (options.renderOptions.preferredRenderMode) {
      RenderOptions.RenderMode.Texture -> AndroidMapSurfaceKind.Texture
      RenderOptions.RenderMode.Surface -> AndroidMapSurfaceKind.Surface
    }
  key(surfaceKind, renderBackend) {
    MlnFfiMapView(
      renderBackend = renderBackend,
      surface = { renderer, surfaceModifier, surfaceLogger, presentFrames ->
        AndroidMlnFfiSurface(
          renderer = renderer,
          runtimeBackends = runtimeBackends,
          backend = renderBackend,
          kind = surfaceKind,
          maximumFps = options.renderOptions.maximumFps,
          modifier = surfaceModifier,
          logger = surfaceLogger,
          presentWindow = presentFrames,
        )
      },
      modifier = modifier,
      state = state,
      options = options,
    )
  }
}
