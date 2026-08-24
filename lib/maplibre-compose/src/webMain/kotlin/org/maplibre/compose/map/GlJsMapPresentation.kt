package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalLayoutDirection
import org.maplibre.compose.interaction.internal.FeatureClickDispatcher
import web.html.HTMLElement

/** Shares session ownership and style replay between Compose and DOM hosts. */
@Composable
internal fun <T> GlJsMapPresentation(
  state: MapState,
  owner: MapPresentationOwnerToken,
  options: MapViewOptions,
  container: HTMLElement? = null,
  content: @Composable (GlJsMapSession, FeatureClickDispatcher) -> T,
): T? {
  return MapPresentationContent(state, owner, options) { binding ->
    val layoutDirection = LocalLayoutDirection.current
    val callbacks = binding.callbacks
    val logger = state.runtime.logger
    val session =
      state.lifecycle.adapter {
        GlJsMapSession(
          lifecycleAuthority = state.lifecycle,
          callbacks = callbacks,
          logger = logger,
          layoutDirection = layoutDirection,
          requests = state.runtime.jsRequests,
          mapContainer = container,
        )
      } as GlJsMapSession

    SideEffect { session.mapContainer = container }
    session.callbacks = callbacks
    session.logger = logger
    session.layoutDirection = layoutDirection
    val currentOnReset = rememberUpdatedState(binding.onReset)

    // Must run in the apply phase, not from a coroutine: the unload has to precede the content
    // subcomposition inserting layers, or a style switch inserts them against the base style being
    // replaced (see #269).
    SideEffect { session.setBaseStyle(state.style.baseStyle) }
    if (session.hasUsableViewport) {
      SideEffect {
        if (session.hasUsableViewport) {
          binding.update(session)
        }
      }
    }

    LaunchedEffect(session) { session.start() }

    DisposableEffect(session) {
      onDispose {
        currentOnReset.value()
      }
    }

    content(session, binding.clicks)
  }
}
