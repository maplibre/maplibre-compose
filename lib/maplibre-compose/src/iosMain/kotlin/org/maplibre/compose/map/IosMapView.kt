package org.maplibre.compose.map

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable internal actual fun mapPresentationHostIdentity(): Any = Unit

@Composable
internal actual fun rememberComposeMapPresentation(
  state: MapState,
  presentationOwner: MapPresentationOwnerToken,
  options: MapViewOptions,
): ComposeMapPresentation? {
  val presentation =
    remember(state, presentationOwner) {
      AppleMapPresentation(state, presentationOwner, options)
    }
  DisposableEffect(presentation) { onDispose { presentation.close() } }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(presentation, lifecycle) {
    val observer = LifecycleEventObserver { _, _ ->
      if (!state.isClosed && !presentation.isClosed) {
        presentation.isActive = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
      }
    }
    lifecycle.addObserver(observer)
    presentation.isActive = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    onDispose { lifecycle.removeObserver(observer) }
  }
  return presentation.Content(options) { session, clicks ->
    ComposeMapPresentation(
      session,
      clicks,
      { state.attachmentAuthority.setEngaged(session, it) },
    ) { modifier ->
      MlnFfiMapSurfaceContent(session, options, modifier) { surfaceModifier, revealSurface ->
        if (revealSurface) {
          UIKitView(
            modifier = surfaceModifier,
            factory = { MaplibreMapView(presentation).apply { userInteractionEnabled = false } },
            onRelease = { it.detach() },
            properties =
              UIKitInteropProperties(isInteractive = false, isNativeAccessibilityEnabled = false),
          )
        } else {
          Box(surfaceModifier)
        }
      }
    }
  }
}
