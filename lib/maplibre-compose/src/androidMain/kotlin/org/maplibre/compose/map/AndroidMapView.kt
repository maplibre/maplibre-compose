package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import org.maplibre.compose.mlnffi.AndroidMlnFfiSurface
import org.maplibre.compose.mlnffi.MapRenderBackend

@Composable internal actual fun mapPresentationHostIdentity(): Any = Unit

@Composable
internal actual fun rememberComposeMapPresentation(
  state: MapState,
  presentationOwner: MapPresentationOwnerToken,
  options: MapViewOptions,
): ComposeMapPresentation? {
  val logger = state.runtime.logger
  val runtimeBackends = remember { loadRuntimeBackends(logger) }
  val renderBackend =
    remember(runtimeBackends) { runtimeBackends.firstOrNull() ?: MapRenderBackend.OPENGL }
  val renderMode = options.uiOptions.renderMode
  return key(renderMode, renderBackend) {
    rememberMlnFfiComposeMapPresentation(
      renderBackend = renderBackend,
      surface = { renderer, surfaceModifier, surfaceLogger, presentFrames ->
        key(renderMode, renderBackend) {
          AndroidMlnFfiSurface(
            renderer = renderer,
            runtimeBackends = runtimeBackends,
            backend = renderBackend,
            renderMode = renderMode,
            maximumFps = options.renderOptions.maximumFps,
            modifier = surfaceModifier,
            logger = surfaceLogger,
            presentWindow = presentFrames,
          )
        }
      },
      state = state,
      presentationOwner = presentationOwner,
      options = options,
    )
  }
}
