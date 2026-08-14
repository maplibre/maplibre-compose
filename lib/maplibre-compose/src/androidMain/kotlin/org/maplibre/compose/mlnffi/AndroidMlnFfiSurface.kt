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
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.touchlab.kermit.Logger

/** An Android surface that the OpenGL FFI runtime presents into directly. */
@Composable
internal fun AndroidMlnFfiSurface(
  renderer: MlnFfiMapRenderer,
  runtimeBackends: Set<MapRenderBackend>,
  kind: AndroidMapSurfaceKind = AndroidMapSurfaceKind.Texture,
  maximumFps: Int? = null,
  modifier: Modifier,
  logger: Logger?,
) {
  val density = LocalDensity.current.density.toDouble()
  val lifecycleOwner = LocalLifecycleOwner.current
  val displayRefreshHz = LocalView.current.display?.refreshRate ?: 60f
  val controller =
    remember(renderer) {
      AndroidMlnFfiSurfaceController(renderer, logger, maximumFps, displayRefreshHz)
    }
  val available = MapRenderBackend.OPENGL in runtimeBackends

  SideEffect { controller.setFrameRateVote(maximumFps, displayRefreshHz) }

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
        "Android MapLibre Compose requires the OpenGL maplibre-native-ffi runtime; " +
          "available backends: ${runtimeBackends.joinToString().ifEmpty { "none" }}"
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
        zOrder = AndroidExternalSurfaceZOrder.OnTop,
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
