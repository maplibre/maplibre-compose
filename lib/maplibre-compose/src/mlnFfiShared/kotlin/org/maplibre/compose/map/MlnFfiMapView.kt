package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import co.touchlab.kermit.Logger
import org.maplibre.compose.mlnffi.LocalMlnFfiMapHostFactory
import org.maplibre.compose.mlnffi.LocalMlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiMapHostFactory
import org.maplibre.compose.mlnffi.MlnFfiMapSurface
import org.maplibre.compose.style.BaseStyle
import org.maplibre.nativeffi.Maplibre
import org.maplibre.nativeffi.render.RenderBackend

/** A map backed by MapLibre Native FFI, rendered through [hostFactory]. */
@Composable
internal fun MlnFfiMapView(
  hostFactory: MlnFfiMapHostFactory,
  modifier: Modifier,
  style: BaseStyle,
  update: (map: MapAdapter) -> Unit,
  onReset: () -> Unit,
  logger: Logger?,
  callbacks: MapAdapter.Callbacks,
  options: MapOptions,
) {
  val factory = LocalMlnFfiMapHostFactory.current ?: hostFactory
  val runtimeOptions = LocalMlnFfiRuntimeOptions.current
  val layoutDirection = LocalLayoutDirection.current
  val density = LocalDensity.current

  // Safe to call off the owner thread: it only inspects what the loaded library was built with.
  val runtimeBackends = remember { loadRuntimeBackends(logger) }

  val session =
    remember(factory, layoutDirection, runtimeOptions) {
      MlnFfiMapSession(
        callbacks = callbacks,
        logger = logger,
        renderBackend = preferredBackend(runtimeBackends),
        layoutDirection = layoutDirection,
        runtimeOptions = runtimeOptions,
      )
    }

  session.callbacks = callbacks
  session.logger = logger
  val currentOnReset = rememberUpdatedState(onReset)

  // Must run in the apply phase, not from a coroutine: the unload has to precede the content
  // subcomposition inserting layers, or a style switch crashes on anchor validation (see #269).
  SideEffect { session.setBaseStyle(style) }

  LaunchedEffect(session, options, update) { update(session) }

  DisposableEffect(session) { onDispose { currentOnReset.value() } }

  // Held here rather than inside the modifier so it survives recomposition.
  val focusRequester = remember { FocusRequester() }
  val touchMomentum = remember(session) { TouchMomentum() }

  MlnFfiMapSurface(
    renderer = session,
    runtimeBackends = runtimeBackends,
    factory = factory,
    modifier =
      modifier.mlnFfiMapInput(
        session,
        options.gestureOptions,
        density,
        focusRequester,
        touchMomentum,
      ),
    logger = logger,
  )
}

/**
 * Reports which backends the packaged MapLibre Native FFI runtime was built with. Empty rather than
 * throwing when no runtime is on the classpath; negotiation reports that as a diagnostic.
 */
private fun loadRuntimeBackends(logger: Logger?): Set<MapRenderBackend> =
  try {
    Maplibre.loadNativeLibrary()
    Maplibre.supportedRenderBackends().mapNotNullTo(mutableSetOf()) { it.toComposeBackend() }
  } catch (error: Throwable) {
    if (error is VirtualMachineError) throw error
    logger?.e(error) { "Could not load the MapLibre Native FFI runtime" }
    emptySet()
  }

/**
 * The MapLibre Compose producer backend this FFI backend corresponds to, or null when no host
 * bridge exists for it — WebGPU today, which the FFI builds only for the browser.
 */
private fun RenderBackend.toComposeBackend(): MapRenderBackend? =
  when (this) {
    RenderBackend.METAL -> MapRenderBackend.METAL
    RenderBackend.VULKAN -> MapRenderBackend.VULKAN
    RenderBackend.OPENGL -> MapRenderBackend.OPENGL
    RenderBackend.WEBGPU -> null
  }

/**
 * Picks the backend the session will ask the host for. The host factory has the final say during
 * negotiation; this only has to name something the runtime can actually do.
 */
private fun preferredBackend(runtimeBackends: Set<MapRenderBackend>): MapRenderBackend =
  when {
    MapRenderBackend.METAL in runtimeBackends -> MapRenderBackend.METAL
    MapRenderBackend.VULKAN in runtimeBackends -> MapRenderBackend.VULKAN
    else -> MapRenderBackend.OPENGL
  }
