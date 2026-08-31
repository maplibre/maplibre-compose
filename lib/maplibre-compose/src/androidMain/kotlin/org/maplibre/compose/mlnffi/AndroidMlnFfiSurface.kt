package org.maplibre.compose.mlnffi

import androidx.compose.foundation.AndroidEmbeddedExternalSurface
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.AndroidExternalSurfaceZOrder
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.touchlab.kermit.Logger
import org.maplibre.compose.map.mlnFfiArchitecture
import org.maplibre.compose.map.mlnFfiOperatingSystem

/** An Android surface that the FFI runtime presents into directly. */
@Composable
internal fun AndroidMlnFfiSurface(
  renderer: MlnFfiMapRenderer,
  runtimeBackends: Set<MapRenderBackend>,
  backend: MapRenderBackend,
  kind: AndroidMapSurfaceKind,
  maximumFps: Int? = null,
  modifier: Modifier,
  logger: Logger?,
  presentWindow: Boolean = true,
) {
  val density = LocalDensity.current.density.toDouble()
  val lifecycleOwner = LocalLifecycleOwner.current
  val controller =
    remember(renderer, backend) {
      AndroidMlnFfiSurfaceController(renderer, backend, logger, maximumFps)
    }
  val available = backend in runtimeBackends

  SideEffect { controller.setMaximumFps(maximumFps) }

  DisposableEffect(controller, lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START -> controller.setActive(true)
        Lifecycle.Event.ON_STOP -> controller.setActive(false)
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    controller.setActive(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  DisposableEffect(controller) { onDispose { controller.close() } }

  if (!available) {
    DisposableEffect(runtimeBackends) {
      logger?.e {
        val diagnostic =
          backendDiagnostic(
            runtimeBackends = runtimeBackends,
            hostBridges = listOf(RenderBackendPair(backend, ComposeRenderBackend.OPENGL)),
            hostDescription = "the Android $backend surface host",
            operatingSystem = mlnFfiOperatingSystem,
            architecture = mlnFfiArchitecture,
          )
        "Android MapLibre Compose cannot present a map\n${diagnostic ?: "backend $backend"}"
      }
      onDispose {}
    }
    return
  }

  // An empty SurfaceView would punch a black hole; styles load without a surface.
  if (!presentWindow) {
    Box(modifier)
    return
  }

  when (kind) {
    AndroidMapSurfaceKind.Texture ->
      // Never opaque: before its first swap a TextureView would otherwise fill with black.
      AndroidEmbeddedExternalSurface(modifier = modifier, isOpaque = false) {
        onSurface { surface, width, height ->
          controller.surfaceCreated(surface, width, height, density)
          surface.onChanged { changedWidth, changedHeight ->
            controller.surfaceChanged(changedWidth, changedHeight, density)
          }
          surface.onDestroyed { controller.surfaceDestroyed() }
        }
      }
    AndroidMapSurfaceKind.Surface ->
      // Do not reuse a SurfaceView whose callback belongs to a disposed renderer session.
      // https://issuetracker.google.com/issues/554586999
      key(controller) {
        AndroidExternalSurface(
          // Force a traversal for a replacement SurfaceView that would otherwise wait for another
          // window invalidation. https://issuetracker.google.com/issues/554732248
          modifier = modifier.graphicsLayer(),
          // Never opaque: before its first swap the hole would otherwise read as black.
          isOpaque = false,
          // Behind the window, matching MapLibre's MapView: Compose overlays draw on top of the
          // map.
          zOrder = AndroidExternalSurfaceZOrder.Behind,
        ) {
          onSurface { surface, width, height ->
            controller.surfaceCreated(surface, width, height, density)
            surface.onChanged { changedWidth, changedHeight ->
              controller.surfaceChanged(changedWidth, changedHeight, density)
            }
            surface.onDestroyed { controller.surfaceDestroyed() }
          }
        }
      }
  }
}
