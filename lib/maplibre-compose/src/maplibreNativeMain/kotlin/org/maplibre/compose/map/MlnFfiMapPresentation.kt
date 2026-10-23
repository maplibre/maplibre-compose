package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import kotlinx.coroutines.CancellationException
import org.maplibre.compose.interaction.internal.FeatureClickDispatcher
import org.maplibre.compose.mlnffi.MapRenderBackend

/** Owns one presentation of the map state's adapter. Density changes recreate its native map. */
@Composable
internal fun <T> MlnFfiMapPresentation(
  renderBackend: MapRenderBackend,
  state: MapState,
  presentationOwner: MapPresentationOwnerToken,
  options: MapViewOptions,
  content: @Composable (MlnFfiMapSession, FeatureClickDispatcher, Boolean) -> T,
): T? {
  if (state.isClosed) return null
  val compatibility = renderBackend to LocalDensity.current.density.toDouble()
  return key(compatibility, presentationOwner) {
    MapPresentationContent(state, presentationOwner, options) { binding ->
      val prepared = remember { mutableStateOf(false) }
      val session =
        rememberMlnFfiMapSession(compatibility, state, binding) { prepared.value = true }
      content(session, binding.clicks, prepared.value && session.canPresentFrames)
    }
  }
}

@Composable
private fun rememberMlnFfiMapSession(
  compatibility: Pair<MapRenderBackend, Double>,
  state: MapState,
  binding: MapPresentationBinding,
  onPrepared: () -> Unit,
): MlnFfiMapSession {
  val layoutDirection = LocalLayoutDirection.current
  val renderBackend = compatibility.first
  val scaleFactor = compatibility.second
  val logger = state.runtime.logger
  val callbacks = binding.callbacks
  val style = state.style.baseStyle
  val session =
    state.lifecycle.adapter {
      MlnFfiMapSession(
        lifecycleAuthority = state.lifecycle,
        callbacks = callbacks,
        logger = logger,
        renderBackend = renderBackend,
        scaleFactor = scaleFactor,
        layoutDirection = layoutDirection,
        owner = state.runtime.nativeOwner,
      )
    } as MlnFfiMapSession

  session.durableCallbacks = state.durableStyleCallbacks()
  session.callbacks = callbacks
  session.logger = logger
  session.layoutDirection = layoutDirection
  val currentCallbacks = rememberUpdatedState(callbacks)
  val currentUpdate = rememberUpdatedState(binding.update)
  val currentOnReset = rememberUpdatedState(binding.onReset)

  DisposableEffect(session) {
    session.preparePresentation()
    onPrepared()
    onDispose { currentOnReset.value() }
  }

  // Must run in the apply phase, not from a coroutine: the unload has to precede the content
  // subcomposition inserting layers, or a style switch inserts them against the base style being
  // replaced (see #269).
  SideEffect { session.setBaseStyle(style) }
  SideEffect {
    if (!session.isPresentationPublished) {
      session.setCameraPosition(Snapshot.withoutReadObservation { state.cameraPosition })
    }
    if (
      session.beginPresentationAttachment(renderBackend, scaleFactor) &&
        session.isPresentationPublished
    ) {
      currentUpdate.value(session)
    }
  }
  LaunchedEffect(session) {
    try {
      session.attachPresentation(renderBackend, scaleFactor)
      // A previous presentation's detachment may have installed the durable callbacks after this
      // composition installed its own.
      session.callbacks = currentCallbacks.value
      if (!session.isPresentationPublished) {
        currentUpdate.value(session)
        if (state.currentMapAttachment?.adapter !== session) return@LaunchedEffect
      }
      session.publishRetainedStyle()
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      callbacks.onStyleFailed(session, error.message)
    }
  }

  return session
}
