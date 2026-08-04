package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import co.touchlab.kermit.Logger
import org.maplibre.compose.desktop.DesktopMapSurface
import org.maplibre.compose.desktop.LocalDesktopMapHostFactory
import org.maplibre.compose.desktop.LocalDesktopRuntimeOptions
import org.maplibre.compose.desktop.MapRenderBackend
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.SafeStyle
import org.maplibre.nativeffi.Maplibre
import org.maplibre.nativeffi.render.RenderBackend

@Composable
internal actual fun ComposableMapView(
  modifier: Modifier,
  style: BaseStyle,
  rememberedStyle: SafeStyle?,
  update: (map: MapAdapter) -> Unit,
  onReset: () -> Unit,
  logger: Logger?,
  callbacks: MapAdapter.Callbacks,
  options: MapOptions,
) {
  val factory = LocalDesktopMapHostFactory.current
  val runtimeOptions = LocalDesktopRuntimeOptions.current
  val layoutDirection = LocalLayoutDirection.current
  val density = LocalDensity.current

  // Reading the runtime's backends is the one native call safe to make off the owner thread: it
  // only inspects what the loaded library was built with.
  val runtimeBackends = remember { loadRuntimeBackends(logger) }

  val session =
    remember(factory, layoutDirection, runtimeOptions) {
      DesktopMapSession(
        callbacks = callbacks,
        logger = logger,
        renderBackend = preferredBackend(runtimeBackends),
        layoutDirection = layoutDirection,
        runtimeOptions = runtimeOptions,
      )
    }

  session.callbacks = callbacks
  session.logger = logger

  // Applied from the apply phase, not from a coroutine, because that is when `AndroidView`'s
  // `update` block makes the same call — and the ordering is what keeps a style switch from
  // crashing. Setting a base style unloads the outgoing `SafeStyle`, and `LayerManager` skips
  // anchor validation against an unloaded style (see #269). From a LaunchedEffect that unload
  // happens after every composition has applied, so the content subcomposition has already
  // inserted its layers — anchored to the incoming style's layers, validated against the outgoing
  // style's — and thrown. `setBaseStyle` ignores a repeat, so running every recomposition is free.
  SideEffect { session.setBaseStyle(style) }

  LaunchedEffect(session, options, update) { update(session) }

  DisposableEffect(session) { onDispose { onReset() } }

  // Held here rather than inside the modifier so it survives recomposition; the map takes focus
  // when clicked, which is what lets the keyboard reach it.
  val focusRequester = remember { FocusRequester() }

  DesktopMapSurface(
    renderer = session,
    runtimeBackends = runtimeBackends,
    factory = factory,
    // Input is attached here rather than inside the surface because gestures belong to the map,
    // not to the graphics host: every host gets identical behavior this way.
    modifier = modifier.desktopMapInput(session, options.gestureOptions, density, focusRequester),
    logger = logger,
  )
}

/**
 * Reports which backends the packaged MapLibre Native FFI runtime was built with.
 *
 * Returns an empty set rather than throwing when no runtime is on the classpath; backend
 * negotiation turns that into a diagnostic naming the missing dependency.
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
 * The MapLibre Compose producer backend this FFI backend corresponds to, or null when desktop has
 * no host bridge for it.
 *
 * Null is the designed answer rather than a gap: [loadRuntimeBackends] drops it, and negotiation
 * reports what the runtime offered against what the host supports. WebGPU is the case today — the
 * FFI builds it for the browser, and no desktop host consumes it.
 */
private fun RenderBackend.toComposeBackend(): MapRenderBackend? =
  when (this) {
    RenderBackend.METAL -> MapRenderBackend.METAL
    RenderBackend.VULKAN -> MapRenderBackend.VULKAN
    RenderBackend.OPENGL -> MapRenderBackend.OPENGL
    RenderBackend.WEBGPU -> null
  }

/**
 * Picks the backend the session will ask the host for.
 *
 * The host factory has the final say during negotiation; this only has to name something the
 * runtime can actually do, so the ordering matches the negotiator's preference.
 */
private fun preferredBackend(runtimeBackends: Set<MapRenderBackend>): MapRenderBackend =
  when {
    MapRenderBackend.METAL in runtimeBackends -> MapRenderBackend.METAL
    MapRenderBackend.VULKAN in runtimeBackends -> MapRenderBackend.VULKAN
    else -> MapRenderBackend.OPENGL
  }
