package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import kotlinx.coroutines.CancellationException
import org.maplibre.compose.interaction.internal.FeatureClickDispatcher
import org.maplibre.compose.mlnffi.MapRenderBackend

/**
 * Owns a native presentation and its style composition for one compatible engine. Pixel density and
 * backend changes renew the reservation before selecting a replacement engine, so disposal of the
 * previous session cannot release the replacement's attachment. UI settings and font scale do not
 * change this lifetime.
 */
@Composable
internal fun MlnFfiMapPresentation(
  renderBackend: MapRenderBackend,
  state: MapState,
  presentationOwner: MapPresentationOwnerToken,
  options: MapViewOptions,
  content: @Composable (MlnFfiMapSession, FeatureClickDispatcher) -> Unit,
) {
  val compatibility =
    NativeEngineCompatibility(renderBackend, LocalDensity.current.density.toDouble())
  key(compatibility) {
    MapPresentationContent(state, presentationOwner, options) { binding ->
      val session = rememberMlnFfiMapSession(compatibility, state, binding)
      content(session, binding.clicks)
    }
  }
}

@Composable
private fun rememberMlnFfiMapSession(
  compatibility: NativeEngineCompatibility,
  state: MapState,
  binding: MapPresentationBinding,
): MlnFfiMapSession {
  val applicationOptions = state.runtime.nativeRuntimeOptions
  val layoutDirection = LocalLayoutDirection.current
  val renderBackend = compatibility.renderBackend
  val scaleFactor = compatibility.scaleFactor
  val logger = state.runtime.logger
  val callbacks = binding.callbacks
  val style = state.style.baseStyle
  val retainedSession = state.retainedAdapter(compatibility) as? MlnFfiMapSession

  val unpreparedSession =
    retainedSession
      ?: remember(renderBackend, scaleFactor, applicationOptions, state) {
        MlnFfiMapSession(
          lifecycleAuthority = state.lifecycle,
          callbacks = callbacks,
          logger = logger,
          renderBackend = renderBackend,
          scaleFactor = scaleFactor,
          layoutDirection = layoutDirection,
          cacheFile = applicationOptions.cacheFile,
          resourceProviderFactory = applicationOptions.resourceProviderFactory,
          resourceConfig = state.runtime.resourceConfig,
        )
      }
  val session = remember(unpreparedSession) { unpreparedSession.apply { preparePresentation() } }

  session.durableCallbacks = state.durableStyleCallbacks()
  session.callbacks = callbacks
  session.logger = logger
  session.layoutDirection = layoutDirection
  val currentUpdate = rememberUpdatedState(binding.update)
  val currentOnReset = rememberUpdatedState(binding.onReset)

  // Must run in the apply phase, not from a coroutine: the unload has to precede the content
  // subcomposition inserting layers, or a style switch inserts them against the base style being
  // replaced (see #269).
  SideEffect { session.setBaseStyle(style) }
  SideEffect {
    if (session.beginPresentationAttachment() && session.isPresentationPublished) {
      currentUpdate.value(session)
    }
  }
  LaunchedEffect(session) {
    try {
      session.attachPresentation()
      if (!session.isPresentationPublished) {
        currentUpdate.value(session)
        if (state.currentMapAttachment?.adapter !== session) return@LaunchedEffect
      }
      session.publishRetainedStyle()
    } catch (_: MapClosedException) {
      // A still-mounted UI on a closed map is inert.
    } catch (_: MapLeaseInvalidatedException) {
      // Detach or close won before attach finished.
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      callbacks.onStyleFailed(session, error.message)
    }
  }

  DisposableEffect(session) {
    onDispose {
      currentOnReset.value()
    }
  }

  return session
}
