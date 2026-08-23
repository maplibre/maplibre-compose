package org.maplibre.compose.mlnffi

import androidx.compose.foundation.AndroidEmbeddedExternalSurface
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.AndroidExternalSurfaceZOrder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
        val hostBackends = RenderBackendPair(backend, ComposeRenderBackend.OPENGL)
        val diagnostic =
          backendDiagnostic(
            runtimeBackends = runtimeBackends,
            hostBackends = hostBackends,
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

  when (kind) {
    AndroidMapSurfaceKind.Texture ->
      AndroidEmbeddedExternalSurface(modifier = modifier, isOpaque = true) {
        onSurface { surface, width, height ->
          controller.surfaceCreated(surface, width, height, density)
          surface.onChanged { changedWidth, changedHeight ->
            controller.surfaceChanged(changedWidth, changedHeight, density)
          }
          surface.onDestroyed { controller.surfaceDestroyed() }
        }
      }
    AndroidMapSurfaceKind.Surface ->
      AndroidExternalSurface(
        modifier = modifier,
        isOpaque = true,
        // Behind the window, matching MapLibre's MapView: Compose overlays draw on top of the map.
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
