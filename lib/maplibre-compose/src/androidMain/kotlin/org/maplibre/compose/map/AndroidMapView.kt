package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import co.touchlab.kermit.Logger
import org.maplibre.compose.mlnffi.AndroidMapSurfaceKind
import org.maplibre.compose.mlnffi.AndroidMlnFfiPlatform
import org.maplibre.compose.mlnffi.AndroidMlnFfiSurface
import org.maplibre.compose.mlnffi.EnsureMlnFfiConfigured
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleBinding

@Composable internal actual fun mapPresentationHostIdentity(): Any = Unit

@Composable
internal actual fun ComposableMapView(
  modifier: Modifier,
  runtime: RuntimeImplementation?,
  state: MapState?,
  style: BaseStyle,
  rememberedStyle: StyleBinding?,
  update: (map: MapAdapter) -> Unit,
  onReset: () -> Unit,
  logger: Logger?,
  callbacks: MapAdapter.Callbacks,
  options: MapPresentationOptions,
) {
  if (runtime == null) EnsureMlnFfiConfigured()
  else AndroidMlnFfiPlatform.initialize(LocalContext.current)
  val runtimeBackends = remember { loadRuntimeBackends(logger) }
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
      runtimeOptions = runtime?.nativeRuntimeOptions,
      style = style,
      update = update,
      onReset = onReset,
      logger = logger,
      callbacks = callbacks,
      options = options,
    )
  }
}
