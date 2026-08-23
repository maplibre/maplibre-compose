package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import co.touchlab.kermit.Logger
import org.maplibre.compose.mlnffi.AndroidMapSurfaceKind
import org.maplibre.compose.mlnffi.AndroidMlnFfiSurface
import org.maplibre.compose.mlnffi.EnsureMlnFfiConfigured
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.SafeStyle

@Composable
internal actual fun ComposableMapView(
  modifier: Modifier,
  style: BaseStyle,
  rememberedStyle: SafeStyle?,
  update: (map: MapAdapter) -> Unit,
  onReset: () -> Unit,
  logger: Logger?,
  callbacks: MapAdapter.Callbacks,
  options: MapOptions,
) {
  EnsureMlnFfiConfigured()
  val runtimeBackends = remember { loadRuntimeBackends(logger) }
  val renderBackend = remember(runtimeBackends) { pickRenderBackend(runtimeBackends) }
  val surfaceKind =
    when (options.renderOptions.preferredRenderMode) {
      RenderOptions.RenderMode.Texture -> AndroidMapSurfaceKind.Texture
      RenderOptions.RenderMode.Surface -> AndroidMapSurfaceKind.Surface
    }
  key(surfaceKind, renderBackend) {
    MlnFfiMapView(
      renderBackend = renderBackend,
      surface = { renderer, surfaceModifier, surfaceLogger ->
        AndroidMlnFfiSurface(
          renderer = renderer,
          runtimeBackends = runtimeBackends,
          backend = renderBackend,
          kind = surfaceKind,
          maximumFps = options.renderOptions.maximumFps,
          modifier = surfaceModifier,
          logger = surfaceLogger,
        )
      },
      modifier = modifier,
      style = style,
      update = update,
      onReset = onReset,
      logger = logger,
      callbacks = callbacks,
      options = options,
    )
  }
}

/**
 * The backend the Android surface hosts drive, from what the packaged runtime renders with. The
 * runtime artifacts name one `libmaplibre-native-c.so`, so exactly one backend can be packaged;
 * Vulkan wins when a runtime carrying both ever appears.
 */
private fun pickRenderBackend(runtimeBackends: Set<MapRenderBackend>): MapRenderBackend =
  if (MapRenderBackend.VULKAN in runtimeBackends) MapRenderBackend.VULKAN
  else MapRenderBackend.OPENGL
